#pragma once

#include <chrono>
#include <cstring>
#include <filesystem>
#include <string>
#include <vector>

#ifdef _WIN32
#include <windows.h>
#else
#include <fcntl.h>
#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>
#ifdef __linux__
#include <sys/syscall.h>
#endif
#endif

struct LaunchedProcess {
    int slot_number = 0;
    int bot_id = 0;
    int port = 0;
    unsigned long pid = 0;
#ifdef _WIN32
    HANDLE process = nullptr;
    HANDLE job = nullptr;
#endif
    using Clock = std::chrono::steady_clock;
    Clock::time_point stop_deadline{};
    bool restart_pending = false;
    bool stop_requested = false;
    bool container = false;
    bool container_ready = false;
    std::filesystem::path ready_path;
    std::string exit_status;
    std::string username;
    std::string command;
    std::string working_directory;
    std::filesystem::path log_path;
};

static bool process_running(const LaunchedProcess& instance) {
    return instance.pid != 0;
}

static bool launch_process(LaunchedProcess& instance, std::string& error) {
    if (process_running(instance)) {
        error = "instance is already running";
        return false;
    }

    std::error_code directory_error;
    std::filesystem::create_directories(instance.log_path.parent_path(), directory_error);
    if (directory_error) {
        error = "cannot create log directory: " + directory_error.message();
        return false;
    }
#ifdef _WIN32
    HANDLE job = CreateJobObjectW(nullptr, nullptr);
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (!job || !SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits))) {
        error = "cannot create process job: " + std::to_string(GetLastError());
        if (job) CloseHandle(job);
        return false;
    }

    SECURITY_ATTRIBUTES inheritable{};
    inheritable.nLength = sizeof(inheritable);
    inheritable.bInheritHandle = TRUE;
    HANDLE log = CreateFileW(instance.log_path.c_str(), GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_DELETE,
                             &inheritable, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    HANDLE input = CreateFileW(L"NUL", GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                               &inheritable, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    SIZE_T size = 0;
    InitializeProcThreadAttributeList(nullptr, 1, 0, &size);
    std::vector<unsigned char> storage(size);
    auto attributes = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(storage.data());
    PROCESS_INFORMATION process{};
    bool initialized = size && InitializeProcThreadAttributeList(attributes, 1, 0, &size);
    if (initialized && log != INVALID_HANDLE_VALUE && input != INVALID_HANDLE_VALUE) {
        HANDLE inherited[] = {input, log};
        if (UpdateProcThreadAttribute(attributes, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
                                      inherited, sizeof(inherited), nullptr, nullptr)) {
            STARTUPINFOEXA startup{};
            startup.StartupInfo.cb = sizeof(startup);
            startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
            startup.StartupInfo.hStdInput = input;
            startup.StartupInfo.hStdOutput = log;
            startup.StartupInfo.hStdError = log;
            startup.lpAttributeList = attributes;
            std::string command = "cmd.exe /D /S /C \"" + instance.command + "\"";
            const char* directory = instance.working_directory.empty() ? nullptr : instance.working_directory.c_str();
            CreateProcessA(nullptr, command.data(), nullptr, nullptr, TRUE,
                           CREATE_SUSPENDED | CREATE_NO_WINDOW | BELOW_NORMAL_PRIORITY_CLASS
                           | EXTENDED_STARTUPINFO_PRESENT, nullptr, directory, &startup.StartupInfo, &process);
        }
    }
    DWORD launch_error = GetLastError();
    if (initialized) DeleteProcThreadAttributeList(attributes);
    if (log != INVALID_HANDLE_VALUE) CloseHandle(log);
    if (input != INVALID_HANDLE_VALUE) CloseHandle(input);
    if (!process.hProcess) {
        CloseHandle(job);
        error = "cannot launch process: " + std::to_string(launch_error);
        return false;
    }
    if (!AssignProcessToJobObject(job, process.hProcess) || ResumeThread(process.hThread) == DWORD(-1)) {
        error = "cannot start process in its job: " + std::to_string(GetLastError());
        TerminateProcess(process.hProcess, 1);
        CloseHandle(process.hThread);
        CloseHandle(process.hProcess);
        CloseHandle(job);
        return false;
    }
    CloseHandle(process.hThread);
    instance.job = job;
    instance.process = process.hProcess;
    instance.pid = process.dwProcessId;
#else
    int log = open(instance.log_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    int input = open("/dev/null", O_RDONLY | O_CLOEXEC);
    if (log < 0 || input < 0) {
        error = "cannot open instance log or input";
        if (log >= 0) close(log);
        if (input >= 0) close(input);
        return false;
    }
    const char* command = instance.command.c_str();
    const char* directory = instance.working_directory.c_str();
    long max_fd = sysconf(_SC_OPEN_MAX);
    pid_t pid = fork();
    if (pid == 0) {
        if (setpgid(0, 0) != 0 || dup2(input, STDIN_FILENO) < 0
                || dup2(log, STDOUT_FILENO) < 0 || dup2(log, STDERR_FILENO) < 0) _exit(126);
#ifdef SYS_close_range
        if (syscall(SYS_close_range, 3u, ~0u, 0u) != 0)
#endif
            for (long fd = 3; fd < max_fd; ++fd) close(static_cast<int>(fd));
        if (*directory && chdir(directory) != 0) {
            constexpr char message[] = "BotFury: cannot enter the launch working directory\n";
            (void)write(STDERR_FILENO, message, sizeof(message) - 1);
            _exit(126);
        }
        execl("/bin/sh", "sh", "-c", command, static_cast<char*>(nullptr));
        _exit(127);
    }
    int fork_error = errno;
    if (pid > 0) (void)setpgid(pid, pid);
    close(log);
    close(input);
    if (pid < 0) {
        error = "cannot fork: " + std::string(std::strerror(fork_error));
        return false;
    }
    instance.pid = static_cast<unsigned long>(pid);
#endif
    instance.stop_deadline = {};
    instance.stop_requested = false;
    instance.exit_status.clear();
    return true;
}

static bool kill_launched_process(LaunchedProcess& instance, std::string& error) {
    if (!process_running(instance)) return false;
#ifdef _WIN32
    if (!TerminateJobObject(instance.job, 1)) {
        error = "cannot kill process job: " + std::to_string(GetLastError());
        return false;
    }
#else
    if (kill(-static_cast<pid_t>(instance.pid), SIGKILL) != 0 && errno != ESRCH) {
        error = "cannot kill process group: " + std::string(std::strerror(errno));
        return false;
    }
#endif
    instance.stop_requested = true;
    instance.stop_deadline = {};
    return true;
}

#ifndef _WIN32
static bool stop_launched_process(LaunchedProcess& instance, std::string& error) {
    if (!process_running(instance)) return false;
    if (instance.stop_requested) return true;
    if (kill(-static_cast<pid_t>(instance.pid), SIGTERM) != 0 && errno != ESRCH) {
        error = "cannot stop process group: " + std::string(std::strerror(errno));
        return false;
    }
    instance.stop_requested = true;
    instance.stop_deadline = LaunchedProcess::Clock::now() + std::chrono::seconds(5);
    return true;
}
#endif

static bool reap_launched_process(LaunchedProcess& instance) {
    if (!process_running(instance)) return false;
#ifdef _WIN32
    if (WaitForSingleObject(instance.process, 0) != WAIT_OBJECT_0) return false;
    DWORD status = 1;
    GetExitCodeProcess(instance.process, &status);
    instance.exit_status = "Exit " + std::to_string(status);
    CloseHandle(instance.job);
    CloseHandle(instance.process);
    instance.job = nullptr;
    instance.process = nullptr;
#else
    pid_t pid = static_cast<pid_t>(instance.pid);
    siginfo_t info{};
    // Keep the leader's PID reserved until the remaining group has been signalled.
    if (waitid(P_PID, static_cast<id_t>(pid), &info, WEXITED | WNOHANG | WNOWAIT) != 0 || !info.si_pid)
        return false;
    if (instance.stop_deadline != LaunchedProcess::Clock::time_point{}
            && LaunchedProcess::Clock::now() < instance.stop_deadline)
        return false;
    (void)kill(-pid, SIGKILL);
    int status = 0;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
    if (WIFEXITED(status)) instance.exit_status = "Exit " + std::to_string(WEXITSTATUS(status));
    else instance.exit_status = "Signal " + std::to_string(WTERMSIG(status));
#endif
    if (instance.stop_requested) instance.exit_status = "Stopped";
    instance.pid = 0;
    instance.stop_deadline = {};
    return true;
}
