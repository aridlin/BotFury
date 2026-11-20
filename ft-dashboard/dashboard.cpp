#define WIN32_LEAN_AND_MEAN
#define NOMINMAX

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <shellapi.h>
#else
#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <signal.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>
#endif

#define FT_IMPLEMENTATION
#include "vendor/ft.hpp"
#include "managed_process.hpp"
#include "window_tiling.hpp"

#include <algorithm>
#include <cerrno>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <csignal>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace fs = std::filesystem;

#ifdef _WIN32
using socket_handle = SOCKET;
static const socket_handle invalid_socket_handle = INVALID_SOCKET;
#else
using socket_handle = int;
static const socket_handle invalid_socket_handle = -1;
#endif

struct DashboardConfig {
    int dashboard_port = 8080;
    int max_bot_instances = 10;
    int bot_port_base = 8765;
    int launch_count = 3;
    int offline_username_start = 1;
    int startup_attach_scan_seconds = 20;
    int server_port = 25565;
    bool auto_attach_launched = true;
    bool auto_launch_on_start = false;
    bool auto_tile_windows = true;
    bool warm_instances = true;
    bool use_containers = false;
    bool disable_rendering_on_start = true;
    std::string server_ip = "localhost";
    std::string offline_username_prefix = "Bot";
    std::string launch_command;
    std::string launch_working_directory;
    std::string webhook_url;
    std::vector<std::string> default_bot_names;
};

struct HttpResult {
    bool ok = false;
    int status = 0;
    std::string body;
    std::string error;
};

struct BotStatus {
    bool reachable = false;
    bool connected = false;
    bool ready = false;
    bool render_enabled = true;
    int http_port = 0;
    int uptime_millis = 0;
    std::string player = "None";
    std::string offline_username;
    std::string error;
};

struct Bot {
    int id = 0;
    std::string name;
    std::string host;
    int port = 0;
    bool in_container = false;
    BotStatus status;
    std::chrono::steady_clock::time_point last_seen{};
};

struct LaunchPreset {
    const char* name;
    const char* command;
    const char* note;
};

enum class ContainerOperation { Stop, Kill, View };

struct ContainerTask {
    ContainerOperation operation;
    LaunchedProcess process;
};

struct AppState {
    DashboardConfig config;
    fs::path config_path;
    fs::path dashboard_dir;
    std::vector<Bot> bots;
    std::vector<LaunchedProcess> launched;
    std::vector<ContainerTask> container_tasks;
    std::vector<ClientWindow> tiled_windows;
    WorkArea tiled_area;
    LaunchedProcess::Clock::time_point next_tile{};
    bool auto_tile_windows = true;
    bool warm_instances = true;
    bool use_containers = false;
    int next_bot_id = 1;
    int tab = 0;
    std::string log = "[boot] FT dashboard initialized";
    std::string launch_message;
    std::chrono::steady_clock::time_point last_refresh{};
    size_t next_refresh_index = 0;

    char server_ip[128]{};
    char server_port[16]{};
    char manual_host[128]{};
    char manual_port[16]{};
    char scan_host[128]{};
    char scan_start_port[16]{};
    char scan_end_port[16]{};
    char broadcast[256]{};
    char launch_count[16]{};
    char port_base[16]{};
    char username_prefix[64]{};
    char username_start[16]{};
    char launch_command[2048]{};
    char launch_cwd[1024]{};
    char max_instances[16]{};
    char attach_scan_seconds[16]{};
    int launch_preset = 0;
    bool auto_attach_launched = true;
    bool auto_launch_on_start = false;
    bool disable_rendering_on_start = true;
};

static const LaunchPreset k_launch_presets[] = {
#ifdef _WIN32
    {
        "Windows batch wrapper",
        "botfury-launch.bat {username} {port} {server_ip} {server_port} {disable_rendering} {auto_join}",
        "Starts the prepared Fabric client with a separate game directory for each bot."
    },
    {
        "PowerShell wrapper",
        "powershell -ExecutionPolicy Bypass -File \".\\botfury-launch.ps1\" -Username \"{username}\" -BotPort {port} -Server \"{server_ip}\" -ServerPort {server_port} -DisableRendering {disable_rendering}",
        "Same idea as the batch wrapper, but easier if your launch logic needs named arguments or path handling."
    },
    {
        "Direct Java command",
        "java -Dbotfury.port={port} -Dbotfury.offlineUsername={username} -Dbotfury.disableRendering={disable_rendering} -jar \"path\\to\\your\\launcher-or-client.jar\"",
        "A bare Java-shaped preset. Replace the jar path and arguments with the exact command your Minecraft setup needs."
    }
#else
    {
        "Linux shell wrapper",
        "sh botfury-launch.sh {username} {port} {server_ip} {server_port} {disable_rendering} {auto_join}",
        "Starts the prepared Fabric client with a separate game directory for each bot."
    },
    {
        "Direct Java command",
        "java -Dbotfury.port={port} -Dbotfury.offlineUsername={username} -Dbotfury.disableRendering={disable_rendering} -jar \"path/to/your/launcher-or-client.jar\"",
        "A bare Java-shaped preset. Replace the jar path and arguments with the exact command your Minecraft setup needs."
    },
    {
        "Windows batch wrapper",
        "botfury-launch.bat {username} {port} {server_ip} {server_port} {disable_rendering} {auto_join}",
        "Windows-only wrapper preset, kept here for shared configs."
    }
#endif
};

static const int k_launch_preset_count = (int)(sizeof(k_launch_presets) / sizeof(k_launch_presets[0]));

static void copy_text(char* dst, size_t cap, const std::string& value) {
    if (!dst || cap == 0) return;
    std::snprintf(dst, cap, "%s", value.c_str());
}

static std::string trim(const std::string& value) {
    size_t first = value.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return "";
    size_t last = value.find_last_not_of(" \t\r\n");
    return value.substr(first, last - first + 1);
}

static std::string first_command_token(const std::string& command) {
    std::string text = trim(command);
    if (text.empty()) return "";

    if (text[0] == '"' || text[0] == '\'') {
        char quote = text[0];
        size_t end = text.find(quote, 1);
        if (end != std::string::npos) return text.substr(1, end - 1);
    }

    size_t end = 0;
    while (end < text.size() && !std::isspace((unsigned char)text[end])) ++end;
    return text.substr(0, end);
}

static std::vector<std::string> command_tokens(const std::string& command) {
    std::vector<std::string> tokens;
    std::string text = trim(command);
    std::string token;
    char quote = '\0';
    bool escape = false;

    for (char ch : text) {
        if (escape) {
            token += ch;
            escape = false;
            continue;
        }
        if (ch == '\\' && quote != '\0') {
            escape = true;
            continue;
        }
        if (quote != '\0') {
            if (ch == quote) quote = '\0';
            else token += ch;
            continue;
        }
        if (ch == '"' || ch == '\'') {
            quote = ch;
            continue;
        }
        if (std::isspace((unsigned char)ch)) {
            if (!token.empty()) {
                tokens.push_back(token);
                token.clear();
            }
            continue;
        }
        token += ch;
    }

    if (!token.empty()) tokens.push_back(token);
    return tokens;
}

static bool equal_ascii_ci(std::string a, std::string b) {
    std::transform(a.begin(), a.end(), a.begin(), [](unsigned char c) { return (char)std::tolower(c); });
    std::transform(b.begin(), b.end(), b.begin(), [](unsigned char c) { return (char)std::tolower(c); });
    return a == b;
}

static int parse_int(const char* text, int fallback, int min_value, int max_value) {
    if (!text || !*text) return fallback;
    char* end = nullptr;
    long value = std::strtol(text, &end, 10);
    if (end == text) return fallback;
    if (value < min_value) return min_value;
    if (value > max_value) return max_value;
    return (int)value;
}

static void append_log(AppState& app, const std::string& line) {
    std::time_t now = std::time(nullptr);
    std::tm tm_buf{};
#ifdef _WIN32
    localtime_s(&tm_buf, &now);
#else
    localtime_r(&now, &tm_buf);
#endif
    char prefix[32];
    std::snprintf(prefix, sizeof(prefix), "[%02d:%02d:%02d] ",
                  tm_buf.tm_hour, tm_buf.tm_min, tm_buf.tm_sec);
    app.log += "\n";
    app.log += prefix;
    app.log += line;

    const size_t max_len = 12000;
    if (app.log.size() > max_len) {
        app.log.erase(0, app.log.size() - max_len);
        size_t first_newline = app.log.find('\n');
        if (first_newline != std::string::npos) app.log.erase(0, first_newline + 1);
    }
}

static std::string read_file(const fs::path& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return "";
    std::ostringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

static size_t json_value_pos(const std::string& text, const std::string& key) {
    std::string needle = "\"" + key + "\"";
    size_t key_pos = text.find(needle);
    if (key_pos == std::string::npos) return std::string::npos;
    size_t colon = text.find(':', key_pos + needle.size());
    if (colon == std::string::npos) return std::string::npos;
    size_t value = text.find_first_not_of(" \t\r\n", colon + 1);
    return value;
}

static std::string json_string_value(const std::string& text, const std::string& key, const std::string& fallback) {
    size_t pos = json_value_pos(text, key);
    if (pos == std::string::npos || text[pos] != '"') return fallback;
    ++pos;
    std::string out;
    bool escape = false;
    for (; pos < text.size(); ++pos) {
        char ch = text[pos];
        if (escape) {
            switch (ch) {
                case 'n': out += '\n'; break;
                case 'r': out += '\r'; break;
                case 't': out += '\t'; break;
                default: out += ch; break;
            }
            escape = false;
        } else if (ch == '\\') {
            escape = true;
        } else if (ch == '"') {
            return out;
        } else {
            out += ch;
        }
    }
    return fallback;
}

static int json_int_value(const std::string& text, const std::string& key, int fallback) {
    size_t pos = json_value_pos(text, key);
    if (pos == std::string::npos) return fallback;
    char* end = nullptr;
    long value = std::strtol(text.c_str() + pos, &end, 10);
    if (end == text.c_str() + pos) return fallback;
    return (int)value;
}

static bool json_bool_value(const std::string& text, const std::string& key, bool fallback) {
    size_t pos = json_value_pos(text, key);
    if (pos == std::string::npos) return fallback;
    if (text.compare(pos, 4, "true") == 0) return true;
    if (text.compare(pos, 5, "false") == 0) return false;
    return fallback;
}

static std::vector<std::string> json_string_array(const std::string& text, const std::string& key) {
    std::vector<std::string> out;
    size_t pos = json_value_pos(text, key);
    if (pos == std::string::npos || text[pos] != '[') return out;
    size_t end = text.find(']', pos + 1);
    if (end == std::string::npos) return out;

    for (size_t i = pos + 1; i < end; ++i) {
        if (text[i] != '"') continue;
        ++i;
        std::string item;
        bool escape = false;
        for (; i < end; ++i) {
            char ch = text[i];
            if (escape) {
                item += ch;
                escape = false;
            } else if (ch == '\\') {
                escape = true;
            } else if (ch == '"') {
                out.push_back(item);
                break;
            } else {
                item += ch;
            }
        }
    }
    return out;
}

static std::string json_escape(const std::string& value) {
    std::string out;
    out.reserve(value.size() + 8);
    for (char ch : value) {
        switch (ch) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += ch; break;
        }
    }
    return out;
}

static DashboardConfig load_config(const fs::path& path) {
    DashboardConfig cfg;
    std::string text = read_file(path);
    if (text.empty()) return cfg;

    cfg.dashboard_port = json_int_value(text, "dashboard_port", cfg.dashboard_port);
    cfg.max_bot_instances = json_int_value(text, "max_bot_instances", cfg.max_bot_instances);
    cfg.bot_port_base = json_int_value(text, "bot_port_base", cfg.bot_port_base);
    cfg.launch_count = json_int_value(text, "launch_count", cfg.launch_count);
    cfg.offline_username_start = json_int_value(text, "offline_username_start", cfg.offline_username_start);
    cfg.startup_attach_scan_seconds = json_int_value(text, "startup_attach_scan_seconds", cfg.startup_attach_scan_seconds);
    cfg.server_port = json_int_value(text, "server_port", cfg.server_port);
    cfg.auto_attach_launched = json_bool_value(text, "auto_attach_launched", cfg.auto_attach_launched);
    cfg.auto_launch_on_start = json_bool_value(text, "auto_launch_on_start", cfg.auto_launch_on_start);
    cfg.auto_tile_windows = json_bool_value(text, "auto_tile_windows", cfg.auto_tile_windows);
    cfg.warm_instances = json_bool_value(text, "warm_instances", cfg.warm_instances);
    cfg.use_containers = json_bool_value(text, "use_containers", cfg.use_containers);
    cfg.disable_rendering_on_start = json_bool_value(text, "disable_rendering_on_start", cfg.disable_rendering_on_start);
    cfg.server_ip = json_string_value(text, "server_ip", cfg.server_ip);
    cfg.offline_username_prefix = json_string_value(text, "offline_username_prefix", cfg.offline_username_prefix);
    cfg.launch_command = json_string_value(text, "launch_command", cfg.launch_command);
    cfg.launch_working_directory = json_string_value(text, "launch_working_directory", cfg.launch_working_directory);
    cfg.webhook_url = json_string_value(text, "webhook_url", cfg.webhook_url);
    cfg.default_bot_names = json_string_array(text, "default_bot_names");
    return cfg;
}

static bool save_config(const fs::path& path, const DashboardConfig& cfg, std::string& error) {
    std::error_code ec;
    fs::create_directories(path.parent_path(), ec);
    if (ec) {
        error = "failed to create config directory: " + ec.message();
        return false;
    }

    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out) {
        error = "failed to open config for writing";
        return false;
    }

    out << "{\n";
    out << "    \"max_bot_instances\": " << cfg.max_bot_instances << ",\n";
    out << "    \"dashboard_port\": " << cfg.dashboard_port << ",\n";
    out << "    \"server_ip\": \"" << json_escape(cfg.server_ip) << "\",\n";
    out << "    \"server_port\": " << cfg.server_port << ",\n";
    out << "    \"bot_port_base\": " << cfg.bot_port_base << ",\n";
    out << "    \"launch_count\": " << cfg.launch_count << ",\n";
    out << "    \"offline_username_prefix\": \"" << json_escape(cfg.offline_username_prefix) << "\",\n";
    out << "    \"offline_username_start\": " << cfg.offline_username_start << ",\n";
    out << "    \"launch_command\": \"" << json_escape(cfg.launch_command) << "\",\n";
    out << "    \"launch_working_directory\": \"" << json_escape(cfg.launch_working_directory) << "\",\n";
    out << "    \"auto_attach_launched\": " << (cfg.auto_attach_launched ? "true" : "false") << ",\n";
    out << "    \"auto_tile_windows\": " << (cfg.auto_tile_windows ? "true" : "false") << ",\n";
    out << "    \"warm_instances\": " << (cfg.warm_instances ? "true" : "false") << ",\n";
    out << "    \"use_containers\": " << (cfg.use_containers ? "true" : "false") << ",\n";
    out << "    \"auto_launch_on_start\": " << (cfg.auto_launch_on_start ? "true" : "false") << ",\n";
    out << "    \"startup_attach_scan_seconds\": " << cfg.startup_attach_scan_seconds << ",\n";
    out << "    \"disable_rendering_on_start\": " << (cfg.disable_rendering_on_start ? "true" : "false") << ",\n";
    out << "    \"default_bot_names\": [";
    for (size_t i = 0; i < cfg.default_bot_names.size(); ++i) {
        if (i) out << ", ";
        out << "\"" << json_escape(cfg.default_bot_names[i]) << "\"";
    }
    out << "],\n";
    out << "    \"rendering_settings\": {\n";
    out << "        \"enabled\": " << (cfg.disable_rendering_on_start ? "false" : "true") << "\n";
    out << "    },\n";
    out << "    \"webhook_url\": \"" << json_escape(cfg.webhook_url) << "\"\n";
    out << "}\n";
    return true;
}

static fs::path find_config_path(const char* argv0) {
    std::vector<fs::path> starts;
    starts.push_back(fs::current_path());
    if (argv0 && *argv0) {
        std::error_code ec;
        fs::path exe = fs::absolute(argv0, ec);
        if (!ec) starts.push_back(exe.parent_path());
    }

    for (fs::path start : starts) {
        for (int i = 0; i < 6 && !start.empty(); ++i) {
            fs::path candidate = start / "config" / "botfury.json";
            if (fs::exists(candidate)) return candidate;
            if (fs::exists(start / "minecraft-bots")) return candidate;
            start = start.parent_path();
        }
    }
    return fs::current_path() / "config" / "botfury.json";
}

static fs::path find_dashboard_dir(const char* argv0, const fs::path& config_path) {
    if (argv0 && *argv0) {
        std::error_code ec;
        fs::path exe_dir = fs::absolute(argv0, ec).parent_path();
        if (!ec) {
            if (exe_dir.filename() == "build") exe_dir = exe_dir.parent_path();
            if (fs::exists(exe_dir / "dashboard.cpp") || exe_dir.filename() == "ft-dashboard") {
                return exe_dir;
            }
        }
    }

    fs::path repo = config_path.parent_path().parent_path();
    if (!repo.empty() && fs::exists(repo / "ft-dashboard")) {
        return repo / "ft-dashboard";
    }

    return fs::current_path();
}

static void sync_config_to_inputs(AppState& app) {
    copy_text(app.server_ip, sizeof(app.server_ip), app.config.server_ip);
    std::snprintf(app.server_port, sizeof(app.server_port), "%d", app.config.server_port);
    copy_text(app.manual_host, sizeof(app.manual_host), "127.0.0.1");
    std::snprintf(app.manual_port, sizeof(app.manual_port), "%d", app.config.bot_port_base);
    copy_text(app.scan_host, sizeof(app.scan_host), "127.0.0.1");
    std::snprintf(app.scan_start_port, sizeof(app.scan_start_port), "%d", app.config.bot_port_base);
    std::snprintf(app.scan_end_port, sizeof(app.scan_end_port), "%d", app.config.bot_port_base + app.config.launch_count - 1);
    std::snprintf(app.launch_count, sizeof(app.launch_count), "%d", app.config.launch_count);
    std::snprintf(app.port_base, sizeof(app.port_base), "%d", app.config.bot_port_base);
    copy_text(app.username_prefix, sizeof(app.username_prefix), app.config.offline_username_prefix);
    std::snprintf(app.username_start, sizeof(app.username_start), "%d", app.config.offline_username_start);
    copy_text(app.launch_command, sizeof(app.launch_command), app.config.launch_command);
    copy_text(app.launch_cwd, sizeof(app.launch_cwd),
              app.config.launch_working_directory.empty()
                  ? app.dashboard_dir.string()
                  : app.config.launch_working_directory);
    std::snprintf(app.max_instances, sizeof(app.max_instances), "%d", app.config.max_bot_instances);
    std::snprintf(app.attach_scan_seconds, sizeof(app.attach_scan_seconds), "%d", app.config.startup_attach_scan_seconds);
    app.auto_attach_launched = app.config.auto_attach_launched;
    app.auto_launch_on_start = app.config.auto_launch_on_start;
    app.auto_tile_windows = app.config.auto_tile_windows;
    app.warm_instances = app.config.warm_instances;
#ifdef __linux__
    app.use_containers = app.config.use_containers;
#endif
    app.disable_rendering_on_start = app.config.disable_rendering_on_start;
}

static void sync_inputs_to_config(AppState& app) {
    app.config.server_ip = trim(app.server_ip);
    app.config.server_port = parse_int(app.server_port, 25565, 1, 65535);
    app.config.launch_count = parse_int(app.launch_count, 1, 1, 64);
    app.config.bot_port_base = parse_int(app.port_base, 8765, 1, 65535);
    app.config.offline_username_prefix = trim(app.username_prefix);
    if (app.config.offline_username_prefix.empty()) app.config.offline_username_prefix = "Bot";
    app.config.offline_username_start = parse_int(app.username_start, 1, 0, 999999);
    app.config.launch_command = trim(app.launch_command);
    app.config.launch_working_directory = trim(app.launch_cwd);
    app.config.max_bot_instances = parse_int(app.max_instances, 10, 1, 512);
    app.config.startup_attach_scan_seconds = parse_int(app.attach_scan_seconds, 20, 0, 300);
    app.config.auto_attach_launched = app.auto_attach_launched;
    app.config.auto_launch_on_start = app.auto_launch_on_start;
    app.config.auto_tile_windows = app.auto_tile_windows;
    app.config.warm_instances = app.warm_instances;
    app.config.use_containers = app.use_containers;
    app.config.disable_rendering_on_start = app.disable_rendering_on_start;
}

static std::string url_encode(const std::string& value) {
    std::ostringstream out;
    out << std::uppercase << std::hex;
    for (unsigned char ch : value) {
        if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') ||
            (ch >= '0' && ch <= '9') || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
            out << (char)ch;
        } else {
            out << '%' << std::setw(2) << std::setfill('0') << (int)ch;
        }
    }
    return out.str();
}

static bool network_ready() {
#ifdef _WIN32
    static bool initialized = false;
    if (!initialized) {
        WSADATA wsa{};
        if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return false;
        initialized = true;
    }
#endif
    return true;
}

static void close_socket_handle(socket_handle sock) {
#ifdef _WIN32
    closesocket(sock);
#else
    close(sock);
#endif
}

static bool set_socket_nonblocking(socket_handle sock, bool nonblocking) {
#ifdef _WIN32
    u_long mode = nonblocking ? 1 : 0;
    return ioctlsocket(sock, FIONBIO, &mode) == 0;
#else
    int flags = fcntl(sock, F_GETFL, 0);
    if (flags < 0) return false;
    if (nonblocking) flags |= O_NONBLOCK;
    else flags &= ~O_NONBLOCK;
    return fcntl(sock, F_SETFL, flags) == 0;
#endif
}

static bool would_block() {
#ifdef _WIN32
    int e = WSAGetLastError();
    return e == WSAEWOULDBLOCK || e == WSAEINPROGRESS || e == WSAEALREADY;
#else
    return errno == EINPROGRESS || errno == EWOULDBLOCK || errno == EAGAIN;
#endif
}

static void set_socket_timeouts(socket_handle sock, int timeout_ms) {
#ifdef _WIN32
    DWORD timeout = (DWORD)timeout_ms;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, (const char*)&timeout, sizeof(timeout));
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, (const char*)&timeout, sizeof(timeout));
#else
    timeval tv{};
    tv.tv_sec = timeout_ms / 1000;
    tv.tv_usec = (timeout_ms % 1000) * 1000;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
#endif
}

static void open_dashboard_url(int port) {
    std::string url = "http://127.0.0.1:" + std::to_string(port) + "/";
#ifdef _WIN32
    ShellExecuteA(nullptr, "open", url.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
#elif defined(__APPLE__)
    std::string command = "open '" + url + "' >/dev/null 2>&1 &";
    std::system(command.c_str());
#else
    std::string command = "xdg-open '" + url + "' >/dev/null 2>&1 &";
    std::system(command.c_str());
#endif
}

static void open_path(const fs::path& path) {
#ifdef _WIN32
    ShellExecuteA(nullptr, "open", path.string().c_str(), nullptr, nullptr, SW_SHOWNORMAL);
#elif defined(__APPLE__)
    std::string command = "open '" + path.string() + "' >/dev/null 2>&1 &";
    std::system(command.c_str());
#else
    std::string command = "xdg-open '" + path.string() + "' >/dev/null 2>&1 &";
    std::system(command.c_str());
#endif
}

static fs::path launch_working_dir(const AppState& app) {
    std::string cwd = trim(app.launch_cwd);
    return cwd.empty() ? app.dashboard_dir : fs::path(cwd);
}

static fs::path batch_wrapper_path(const AppState& app) {
    std::string token = first_command_token(trim(app.launch_command));
    fs::path token_path(token.empty() ? "botfury-launch.bat" : token);
    if (token_path.is_absolute()) return token_path;
    return launch_working_dir(app) / token_path;
}

static void show_startup_error(int port) {
    std::string message =
        "BotFury dashboard could not start a new server. "
        "If another copy is already running, your browser was opened to it.";
#ifdef _WIN32
    MessageBoxA(nullptr,
                message.c_str(),
                "BotFury Dashboard",
                MB_OK | MB_ICONINFORMATION);
#else
    std::fprintf(stderr, "%s Port: %d\n", message.c_str(), port);
#endif
}

static socket_handle connect_with_timeout(const std::string& host, int port, int timeout_ms, std::string& error) {
    if (!network_ready()) {
        error = "network startup failed";
        return invalid_socket_handle;
    }

    addrinfo hints{};
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_family = AF_UNSPEC;

    std::string port_text = std::to_string(port);
    addrinfo* result = nullptr;
    int gai = getaddrinfo(host.c_str(), port_text.c_str(), &hints, &result);
    if (gai != 0 || !result) {
        error = "host lookup failed";
        return invalid_socket_handle;
    }

    socket_handle connected = invalid_socket_handle;
    for (addrinfo* ai = result; ai; ai = ai->ai_next) {
        socket_handle sock = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
        if (sock == invalid_socket_handle) continue;

        set_socket_nonblocking(sock, true);
        int rc = connect(sock, ai->ai_addr, (int)ai->ai_addrlen);
        if (rc == 0 || would_block()) {
            fd_set write_set;
            FD_ZERO(&write_set);
            FD_SET(sock, &write_set);
            timeval tv{};
            tv.tv_sec = timeout_ms / 1000;
            tv.tv_usec = (timeout_ms % 1000) * 1000;
            rc = select((int)(sock + 1), nullptr, &write_set, nullptr, &tv);
            if (rc > 0 && FD_ISSET(sock, &write_set)) {
                int so_error = 0;
#ifdef _WIN32
                int len = sizeof(so_error);
#else
                socklen_t len = sizeof(so_error);
#endif
                getsockopt(sock, SOL_SOCKET, SO_ERROR, (char*)&so_error, &len);
                if (so_error == 0) {
                    set_socket_nonblocking(sock, false);
                    set_socket_timeouts(sock, timeout_ms);
                    connected = sock;
                    break;
                }
            }
        }

        close_socket_handle(sock);
    }

    freeaddrinfo(result);
    if (connected == invalid_socket_handle) error = "connect timed out";
    return connected;
}

static HttpResult http_get(const std::string& host, int port, const std::string& path, int timeout_ms) {
    HttpResult result;
    std::string error;
    socket_handle sock = connect_with_timeout(host, port, timeout_ms, error);
    if (sock == invalid_socket_handle) {
        result.error = error;
        return result;
    }

    std::string request =
        "GET " + path + " HTTP/1.1\r\n"
        "Host: " + host + "\r\n"
        "User-Agent: BotFury-FT-Dashboard/1.0\r\n"
        "Connection: close\r\n\r\n";

    const char* data = request.c_str();
    int remaining = (int)request.size();
    while (remaining > 0) {
#ifdef MSG_NOSIGNAL
        int sent = send(sock, data, remaining, MSG_NOSIGNAL);
#else
        int sent = send(sock, data, remaining, 0);
#endif
        if (sent <= 0) {
            close_socket_handle(sock);
            result.error = "send failed";
            return result;
        }
        data += sent;
        remaining -= sent;
    }

    char buffer[4096];
    std::string response;
    while (response.size() < 1024 * 1024) {
        int got = recv(sock, buffer, sizeof(buffer), 0);
        if (got > 0) {
            response.append(buffer, got);
        } else {
            break;
        }
    }
    close_socket_handle(sock);

    if (response.empty()) {
        result.error = "empty response";
        return result;
    }

    size_t line_end = response.find("\r\n");
    if (line_end != std::string::npos) {
        std::string status_line = response.substr(0, line_end);
        size_t first_space = status_line.find(' ');
        if (first_space != std::string::npos) {
            result.status = std::atoi(status_line.c_str() + first_space + 1);
        }
    }

    size_t body_pos = response.find("\r\n\r\n");
    result.body = body_pos == std::string::npos ? response : response.substr(body_pos + 4);
    result.ok = result.status >= 200 && result.status < 300;
    if (!result.ok && result.error.empty()) result.error = "HTTP " + std::to_string(result.status);
    return result;
}

static BotStatus fetch_bot_status(const Bot& bot, int timeout_ms = 700) {
    BotStatus status;
    HttpResult res = http_get(bot.host, bot.port, "/status", timeout_ms);
    if (!res.ok) {
        status.error = res.error.empty() ? "unreachable" : res.error;
        return status;
    }

    status.reachable = true;
    status.connected = json_bool_value(res.body, "connected", false);
    status.ready = json_bool_value(res.body, "ready", true);
    status.render_enabled = json_bool_value(res.body, "renderEnabled", true);
    status.player = json_string_value(res.body, "player", "None");
    status.offline_username = json_string_value(res.body, "offlineUsername", "");
    status.http_port = json_int_value(res.body, "httpPort", bot.port);
    status.uptime_millis = json_int_value(res.body, "uptimeMillis", 0);
    return status;
}

static std::string command_target(const AppState& app) {
    std::string host = trim(app.server_ip);
    int port = parse_int(app.server_port, 25565, 1, 65535);
    if (host.empty()) host = "localhost";
    return host + ":" + std::to_string(port);
}

static std::string padded_username(const AppState& app, int slot) {
    int start = parse_int(app.username_start, 1, 0, 999999);
    int value = start + slot;
    std::ostringstream ss;
    ss << (trim(app.username_prefix).empty() ? "Bot" : trim(app.username_prefix));
    if (value >= 0 && value < 100) ss << std::setw(2) << std::setfill('0') << value;
    else ss << value;
    return ss.str();
}

static std::string replace_all(std::string value, const std::string& from, const std::string& to) {
    if (from.empty()) return value;
    size_t pos = 0;
    while ((pos = value.find(from, pos)) != std::string::npos) {
        value.replace(pos, from.size(), to);
        pos += to.size();
    }
    return value;
}

static std::string expand_launch_template(const AppState& app, int slot, int port, const std::string& username) {
    std::string command = trim(app.launch_command);
    command = replace_all(command, "{username}", username);
    command = replace_all(command, "{port}", std::to_string(port));
    command = replace_all(command, "{bot_id}", std::to_string(slot + 1));
    command = replace_all(command, "{server_ip}", trim(app.server_ip));
    command = replace_all(command, "{server_port}", std::to_string(parse_int(app.server_port, 25565, 1, 65535)));
    command = replace_all(command, "{cwd}", trim(app.launch_cwd));
    command = replace_all(command, "{disable_rendering}", app.disable_rendering_on_start ? "true" : "false");
    command = replace_all(command, "{auto_join}", app.warm_instances ? "false" : "true");
    return command;
}

static bool known_wrapper_file(const std::string& file_name) {
    return equal_ascii_ci(file_name, "botfury-launch.bat") ||
           equal_ascii_ci(file_name, "botfury-launch.sh");
}

static fs::path wrapper_token_from_command(const std::string& command) {
    std::vector<std::string> tokens = command_tokens(command);
    for (size_t i = 0; i < tokens.size(); ++i) {
        fs::path token_path(tokens[i]);
        std::string file_name = token_path.filename().string();
        if (known_wrapper_file(file_name)) return token_path;
    }
    return {};
}

static bool validate_launch_command(const AppState& app, const std::string& command, std::string& error) {
    std::string first_token = first_command_token(command);
    if (first_token.empty()) {
        error = "launch command is empty";
        return false;
    }

    fs::path token_path = wrapper_token_from_command(command);
    if (token_path.empty()) {
        return true;
    }

    std::string file_name = token_path.filename().string();

    std::error_code ec;
    fs::path base = trim(app.launch_cwd).empty() ? fs::current_path() : fs::path(trim(app.launch_cwd));
    fs::path candidate = token_path.is_absolute() ? token_path : base / token_path;
    if (!fs::exists(candidate, ec)) {
        error = file_name + " was not found in the working directory.";
        return false;
    }

    std::string wrapper_text = read_file(candidate);
    if (wrapper_text.find("BotFury generated this starter wrapper") != std::string::npos &&
        wrapper_text.find("exit /b 64") != std::string::npos) {
        error = candidate.string() + " is still the generated starter wrapper. Replace the exit line with your real Minecraft launch command.";
        return false;
    }
    if (wrapper_text.find("Replace botfury-launch.example.bat with your real Minecraft launch command") != std::string::npos ||
        wrapper_text.find("Copy this file to botfury-launch.bat") != std::string::npos ||
        wrapper_text.find("path\\to\\your\\launcher-or-client.jar") != std::string::npos) {
        error = candidate.string() + " is still an example wrapper. Replace the example text with your real Minecraft launch command.";
        return false;
    }

    return true;
}

static bool run_container_action(AppState& app, LaunchedProcess& instance, ContainerOperation operation,
                                 std::string& error) {
    for (const ContainerTask& task : app.container_tasks) {
        if (task.process.port == instance.port && (task.operation == ContainerOperation::View)
                == (operation == ContainerOperation::View)) {
            error = operation == ContainerOperation::View ? "Viewer already open." : "Container operation in progress.";
            return false;
        }
    }
    ContainerTask task{};
    task.operation = operation;
    task.process.port = instance.port;
    task.process.slot_number = instance.slot_number;
    task.process.username = instance.username;
    task.process.working_directory = app.dashboard_dir.string();
    const char* name = operation == ContainerOperation::Stop ? "stop" :
                       operation == ContainerOperation::Kill ? "kill" : "view";
    task.process.command = std::string("sh botfury-container.sh ") + name + " " + instance.username
                           + " " + std::to_string(instance.port);
    task.process.log_path = instance.log_path.parent_path() / (std::to_string(instance.port) + "-" + name + ".log");
    if (!launch_process(task.process, error)) return false;
    app.container_tasks.push_back(std::move(task));
    if (operation != ContainerOperation::View) {
        instance.stop_requested = true;
        if (!instance.container_ready) {
            std::ofstream cancel(instance.ready_path.string() + ".cancel");
        }
    }
    return true;
}

static bool request_stop_instance(AppState& app, LaunchedProcess& instance, bool force, std::string& error) {
    if (instance.container) {
        return run_container_action(app, instance, force ? ContainerOperation::Kill : ContainerOperation::Stop, error);
    }
#ifndef _WIN32
    if (!force) return stop_launched_process(instance, error);
#endif
    return kill_launched_process(instance, error);
}

static int kill_launched_instances(AppState& app) {
    int killed = 0;
    for (LaunchedProcess& process : app.launched) {
        process.restart_pending = false;
        if (!process_running(process)) continue;

        std::string error;
        if (request_stop_instance(app, process, true, error)) {
            append_log(app, "kill requested for " + process.username);
            ++killed;
        } else {
            append_log(app, "kill failed for " + process.username + ": " + error);
        }
    }

    app.launch_message = "Kill requested for " + std::to_string(killed) + " launched instance(s).";
    if (killed > 0) ft::toast_success(("Kill requested for " + std::to_string(killed) + " instance(s)").c_str());
    else ft::toast_warning("No running launched instances");
    return killed;
}

static int find_bot_by_endpoint(const AppState& app, const std::string& host, int port) {
    for (const Bot& bot : app.bots) {
        if (bot.host == host && bot.port == port) return bot.id;
    }
    return 0;
}

static std::string default_bot_name(const AppState& app, int bot_id) {
    if (bot_id > 0 && (size_t)bot_id <= app.config.default_bot_names.size()) {
        return app.config.default_bot_names[(size_t)bot_id - 1];
    }
    std::ostringstream ss;
    ss << "Bot" << std::setw(2) << std::setfill('0') << bot_id;
    return ss.str();
}

static int attach_bot(AppState& app, const std::string& host, int port, const std::string& preferred_name, bool probe_now = true) {
    if (host.empty() || port < 1 || port > 65535) return -1;
    int existing = find_bot_by_endpoint(app, host, port);
    if (existing) return existing;
    if ((int)app.bots.size() >= app.config.max_bot_instances) return -2;

    Bot bot;
    bot.id = app.next_bot_id++;
    bot.host = host;
    bot.port = port;
    bot.name = preferred_name.empty() ? default_bot_name(app, bot.id) : preferred_name;
    bot.status.error = "waiting for status poll";
    if (probe_now) {
        bot.status = fetch_bot_status(bot, 120);
        if (bot.status.reachable) bot.last_seen = std::chrono::steady_clock::now();
    }
    app.bots.push_back(bot);
    return bot.id;
}

static std::string send_bot_command(const Bot& bot, const std::string& command) {
    if (command.empty()) return "empty command";
    std::string local_command = command;
    if (bot.in_container) {
        for (const char* loopback : {"connect:localhost:", "connect:127.0.0.1:", "connect:[::1]:"}) {
            if (command.rfind(loopback, 0) == 0) {
                local_command = "connect:host.containers.internal:" + command.substr(std::strlen(loopback));
                break;
            }
        }
    }
    HttpResult res = http_get(bot.host, bot.port, "/command?cmd=" + url_encode(local_command), 1200);
    if (!res.ok) return res.error.empty() ? "command failed" : res.error;
    return trim(res.body).empty() ? "ok" : trim(res.body);
}

static void refresh_bots(AppState& app, bool force = false) {
    auto now = std::chrono::steady_clock::now();
    if (!force && app.last_refresh.time_since_epoch().count() > 0 &&
        now - app.last_refresh < std::chrono::milliseconds(250)) {
        return;
    }
    app.last_refresh = now;

    if (app.bots.empty()) return;

    if (force) {
        for (Bot& bot : app.bots) {
            bot.status = fetch_bot_status(bot, 120);
            if (bot.status.reachable) bot.last_seen = now;
        }
        return;
    }

    if (app.next_refresh_index >= app.bots.size()) app.next_refresh_index = 0;
    Bot& bot = app.bots[app.next_refresh_index];
    app.next_refresh_index = (app.next_refresh_index + 1) % app.bots.size();
    bot.status = fetch_bot_status(bot, 40);
    if (bot.status.reachable) bot.last_seen = now;
}

static Bot* find_bot_by_id(AppState& app, int id) {
    for (Bot& bot : app.bots) {
        if (bot.id == id) return &bot;
    }
    return nullptr;
}

static int scan_and_attach(AppState& app, const std::string& host, int start_port, int end_port) {
    int attached = 0;
    if (start_port > end_port) std::swap(start_port, end_port);
    int limit = std::min(end_port, start_port + 255);
    for (int port = start_port; port <= limit; ++port) {
        if ((int)app.bots.size() >= app.config.max_bot_instances) break;
        if (find_bot_by_endpoint(app, host, port)) continue;

        Bot probe;
        probe.host = host;
        probe.port = port;
        BotStatus status = fetch_bot_status(probe, 150);
        if (status.reachable) {
            int id = attach_bot(app, host, port, "", false);
            Bot* bot = find_bot_by_id(app, id);
            if (bot) {
                bot->status = status;
                bot->last_seen = std::chrono::steady_clock::now();
                ++attached;
            }
        }
    }
    return attached;
}

static LaunchedProcess* instance_for_port(AppState& app, int port) {
    for (LaunchedProcess& instance : app.launched) {
        if (instance.port == port) return &instance;
    }
    return nullptr;
}

static bool start_managed_instance(AppState& app, LaunchedProcess& instance) {
    std::string error;
    socket_handle socket = invalid_socket_handle;
    if (instance.container) {
        std::error_code ec;
        fs::remove(instance.ready_path, ec);
        fs::remove(instance.ready_path.string() + ".cancel", ec);
        instance.container_ready = false;
        instance.bot_id = 0;
    } else {
        socket = connect_with_timeout("127.0.0.1", instance.port, 40, error);
    }
    if (socket != invalid_socket_handle) {
        close_socket_handle(socket);
        error = "port " + std::to_string(instance.port) + " is already in use";
    } else if (launch_process(instance, error)) {
        if (!instance.container && app.config.auto_attach_launched) {
            instance.bot_id = std::max(0, attach_bot(app, "127.0.0.1", instance.port, instance.username, false));
            if (Bot* bot = find_bot_by_id(app, instance.bot_id)) bot->in_container = false;
        }
        append_log(app, "started " + instance.username + " on port " + std::to_string(instance.port)
                        + " (PID " + std::to_string(instance.pid) + ")");
        return true;
    }

    instance.exit_status = "Start failed";
    app.launch_message = instance.username + ": " + error;
    append_log(app, app.launch_message);
    ft::toast_error(app.launch_message.c_str());
    return false;
}

static bool launch_slot(AppState& app, int slot) {
    int port = app.config.bot_port_base + slot;
    if (port > 65535) {
        app.launch_message = "Instance port exceeds 65535.";
        return false;
    }
    LaunchedProcess* instance = instance_for_port(app, port);
    if (instance && (process_running(*instance) || instance->restart_pending)) return false;

    std::string username = padded_username(app, slot);
    std::string command = expand_launch_template(app, slot, port, username);
    std::string error;
    if (username.size() > 16 || username.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            "abcdefghijklmnopqrstuvwxyz0123456789_") != std::string::npos) {
        app.launch_message = "Minecraft usernames must have at most 16 letters, digits, or underscores.";
        return false;
    }
    if (app.config.use_containers && (port < 1024 || port > 55535)) {
        app.launch_message = "Container bot ports must be 1024–55535.";
        return false;
    }
    if (!app.config.use_containers && !validate_launch_command(app, command, error)) {
        app.launch_message = error;
        append_log(app, error);
        return false;
    }
    if (std::any_of(app.launched.begin(), app.launched.end(), [&](const LaunchedProcess& other) {
            return other.port != port && (process_running(other) || other.restart_pending)
                    && equal_ascii_ci(other.username, username);
        })) {
        app.launch_message = username + " is already assigned to another active instance.";
        return false;
    }
    if (!instance) {
        app.launched.emplace_back();
        instance = &app.launched.back();
    }
    instance->port = port;
    instance->slot_number = slot + 1;
    instance->username = username;
    instance->container = app.config.use_containers;
    instance->command = command;
    instance->working_directory = app.config.launch_working_directory;
    if (instance->container) {
        instance->working_directory = app.dashboard_dir.string();
        std::string ready_file = "build/instance-logs/" + std::to_string(port) + ".ready";
        instance->ready_path = app.dashboard_dir / ready_file;
        instance->command = "sh botfury-container.sh run " + username + " " + std::to_string(port)
                            + " " + (app.config.disable_rendering_on_start ? "true" : "false") + " " + ready_file;
    }
    instance->log_path = app.dashboard_dir / "build" / "instance-logs" / (std::to_string(port) + ".log");
    return start_managed_instance(app, *instance);
}

static int launch_instances(AppState& app) {
    sync_inputs_to_config(app);
    app.launch_message.clear();
    int count = 0;
    for (int slot = 0; slot < app.config.launch_count; ++slot) {
        if (launch_slot(app, slot)) ++count;
    }
    if (count > 0 && app.launch_message.empty()) {
        app.launch_message = "Started " + std::to_string(count) + " instance(s).";
    }
    return count;
}

static int tile_instances(AppState& app, bool force) {
    std::vector<const LaunchedProcess*> windows;
    for (const LaunchedProcess& instance : app.launched) {
        if (!instance.container) windows.push_back(&instance);
    }
    for (const ContainerTask& task : app.container_tasks) {
        if (task.operation == ContainerOperation::View) windows.push_back(&task.process);
    }
    return tile_client_windows(windows, app.tiled_windows, app.tiled_area, app.auto_tile_windows, force);
}

static void poll_container_tasks(AppState& app) {
    auto& tasks = app.container_tasks;
    for (auto task = tasks.begin(); task != tasks.end();) {
        if (!reap_launched_process(task->process)) {
            ++task;
            continue;
        }
        if (task->process.exit_status != "Exit 0" && task->process.exit_status != "Stopped") {
            if (LaunchedProcess* instance = instance_for_port(app, task->process.port);
                    instance && task->operation != ContainerOperation::View) {
                instance->stop_requested = false;
                instance->restart_pending = false;
            }
            app.launch_message = task->process.username + ": container operation failed. See the instance logs.";
            append_log(app, app.launch_message);
        }
        task = tasks.erase(task);
    }
}

static void poll_instances(AppState& app) {
    poll_container_tasks(app);
    auto now = LaunchedProcess::Clock::now();
    for (LaunchedProcess& instance : app.launched) {
        if (instance.container && process_running(instance) && !instance.container_ready) {
            std::error_code ec;
            if (fs::exists(instance.ready_path, ec)) {
                instance.container_ready = true;
                if (app.config.auto_attach_launched) {
                    instance.bot_id = std::max(0, attach_bot(app, "127.0.0.1", instance.port, instance.username, false));
                    if (Bot* bot = find_bot_by_id(app, instance.bot_id)) bot->in_container = true;
                }
            }
        }
        if (process_running(instance) && instance.stop_deadline != LaunchedProcess::Clock::time_point{}
                && now >= instance.stop_deadline) {
            std::string error;
            if (!kill_launched_process(instance, error)) {
                append_log(app, instance.username + ": " + error);
                instance.stop_deadline = {};
            }
        }
        if (reap_launched_process(instance)) {
            if (instance.container && !instance.container_ready && instance.exit_status != "Stopped") {
                app.launch_message = instance.username + ": container startup failed. See the instance log.";
            }
            for (ContainerTask& task : app.container_tasks) {
                if (task.operation == ContainerOperation::View && task.process.port == instance.port) {
                    std::string error;
                    kill_launched_process(task.process, error);
                }
            }
            if (Bot* bot = find_bot_by_id(app, instance.bot_id)) bot->status = {};
            append_log(app, instance.username + ": " + instance.exit_status);
            if (instance.restart_pending) instance.stop_deadline = now + std::chrono::milliseconds(250);
        }
        if (instance.restart_pending && !process_running(instance) && now >= instance.stop_deadline
                && std::none_of(app.container_tasks.begin(), app.container_tasks.end(), [&](const ContainerTask& task) {
                    return task.process.port == instance.port && task.operation != ContainerOperation::View;
                })) {
            instance.restart_pending = false;
            start_managed_instance(app, instance);
        }
    }
    if (now >= app.next_tile
            && (!app.tiled_windows.empty() || std::any_of(app.launched.begin(), app.launched.end(), process_running))) {
        tile_instances(app, false);
        app.next_tile = now + std::chrono::milliseconds(500);
    }
}

static void save_from_ui(AppState& app) {
    sync_inputs_to_config(app);
    std::string error;
    if (save_config(app.config_path, app.config, error)) {
        append_log(app, "saved config: " + app.config_path.string());
        ft::toast_success("Configuration saved");
    } else {
        append_log(app, "config save failed: " + error);
        ft::toast_error("Configuration save failed");
    }
}

static std::string duration_text(int millis) {
    if (millis <= 0) return "n/a";
    int seconds = millis / 1000;
    if (seconds < 60) return std::to_string(seconds) + "s";
    int minutes = seconds / 60;
    if (minutes < 60) return std::to_string(minutes) + "m " + std::to_string(seconds % 60) + "s";
    int hours = minutes / 60;
    return std::to_string(hours) + "h " + std::to_string(minutes % 60) + "m";
}

static std::string last_seen_text(const Bot& bot) {
    if (bot.last_seen.time_since_epoch().count() == 0) return "never";
    auto elapsed = std::chrono::steady_clock::now() - bot.last_seen;
    int seconds = (int)std::chrono::duration_cast<std::chrono::seconds>(elapsed).count();
    if (seconds < 2) return "now";
    if (seconds < 60) return std::to_string(seconds) + "s ago";
    int minutes = seconds / 60;
    if (minutes < 60) return std::to_string(minutes) + "m ago";
    return std::to_string(minutes / 60) + "h ago";
}

static LaunchedProcess* launched_process_for(AppState& app, const Bot& bot) {
    for (LaunchedProcess& process : app.launched) {
        if ((bot.id > 0 && process.bot_id == bot.id)
                || ((bot.host == "127.0.0.1" || bot.host == "localhost") && process.port == bot.port)) {
            return &process;
        }
    }
    return nullptr;
}

static int running_launched_count(const AppState& app) {
    int count = 0;
    for (const LaunchedProcess& process : app.launched) {
        if (process_running(process)) ++count;
    }
    return count;
}

static void sync_scan_range_to_launch_range(AppState& app) {
    int base = parse_int(app.port_base, 8765, 1, 65535);
    int count = parse_int(app.launch_count, 1, 1, 64);
    std::snprintf(app.scan_start_port, sizeof(app.scan_start_port), "%d", base);
    std::snprintf(app.scan_end_port, sizeof(app.scan_end_port), "%d", std::min(65535, base + count - 1));
}

static void send_to_all(AppState& app, const std::string& command, const std::string& label) {
    int sent = 0;
    int skipped = 0;
    for (const Bot& bot : app.bots) {
        if (!bot.status.reachable) {
            ++skipped;
            continue;
        }
        append_log(app, bot.name + " " + label + " -> " + send_bot_command(bot, command));
        ++sent;
    }
    append_log(app, label + " sent to " + std::to_string(sent) +
                    " reachable bot(s), skipped " + std::to_string(skipped) + " offline bot(s)");
    if (sent == 0) {
        ft::toast_warning("No reachable bots");
    }
}

static void render_bot_card(AppState& app, Bot& bot) {
    std::ostringstream title;
    title << (bot.status.reachable ? "[online] " : "[offline] ")
          << bot.name << "  " << bot.host << ":" << bot.port;
    if (bot.status.connected) title << "  in game";
    else if (bot.status.reachable) title << "  title screen";
    bool open = true;
    if (!ft::collapsing_header(title.str().c_str(), &open) || !open) return;

    std::string reach = bot.status.reachable ? "reachable" : "offline";
    std::string in_game = bot.status.connected ? "in game" : "not in game";
    std::string render = bot.status.render_enabled ? "render on" : "render off";
    std::string player = bot.status.player.empty() ? "None" : bot.status.player;
    LaunchedProcess* process = launched_process_for(app, bot);
    std::string proc = process ? (process_running(*process) ? "running" : "exited") : "untracked";

    std::string line = "Status: " + reach + " | " + in_game + " | " + render +
                       " | player: " + player +
                       " | uptime: " + duration_text(bot.status.uptime_millis) +
                       " | last seen: " + last_seen_text(bot) +
                       " | process: " + proc;
    if (process && process->pid) line += " pid=" + std::to_string(process->pid);
    if (!bot.status.offline_username.empty()) line += " | username: " + bot.status.offline_username;
    if (!bot.status.reachable && !bot.status.error.empty()) line += " | " + bot.status.error;
    ft::text_wrapped(line.c_str());

    ft::row(6, [&]() {
        if (ft::button("Connect", ft::ColorRole::Accent)) {
            std::string result = send_bot_command(bot, "connect:" + command_target(app));
            append_log(app, bot.name + " connect -> " + result);
        }
        if (ft::button("Refresh")) {
            bot.status = fetch_bot_status(bot, 700);
            if (bot.status.reachable) bot.last_seen = std::chrono::steady_clock::now();
            append_log(app, bot.name + " refreshed");
        }
        if (ft::button("Toggle Render")) {
            std::string result = send_bot_command(bot, "toggle_render");
            append_log(app, bot.name + " toggle render -> " + result);
        }
        if (ft::button("Stop Baritone")) {
            std::string result = send_bot_command(bot, "chat:#stop");
            append_log(app, bot.name + " #stop -> " + result);
        }
        if (ft::button(("Kill##fleet-" + std::to_string(bot.id)).c_str(), ft::ColorRole::Warning)) {
            if (process && process_running(*process)) {
                std::string error;
                process->restart_pending = false;
                if (request_stop_instance(app, *process, true, error)) append_log(app, bot.name + " kill requested");
                else append_log(app, bot.name + " process kill failed: " + error);
            } else {
                append_log(app, bot.name + " has no running tracked process");
            }
        }
        if (ft::button("Detach", ft::ColorRole::Warning)) {
            int id = bot.id;
            app.bots.erase(std::remove_if(app.bots.begin(), app.bots.end(),
                           [id](const Bot& candidate) { return candidate.id == id; }),
                           app.bots.end());
            append_log(app, "detached bot " + std::to_string(id));
        }
    });
}

static void render_fleet_tab(AppState& app) {
    int reachable = 0;
    int connected = 0;
    int render_off = 0;
    for (const Bot& bot : app.bots) {
        if (bot.status.reachable) ++reachable;
        if (bot.status.connected) ++connected;
        if (bot.status.reachable && !bot.status.render_enabled) ++render_off;
    }

    int total = (int)app.bots.size();
    int running = running_launched_count(app);
    float health = total > 0 ? (float)reachable / (float)total : 0.0f;
    std::string overview =
        "Fleet: " + std::to_string(total) +
        " attached | " + std::to_string(reachable) + " reachable | " +
        std::to_string(connected) + " in game | " +
        std::to_string(render_off) + " render off | " +
        std::to_string(running) + " launched process(es) running";
    ft::text_wrapped(overview.c_str());
    ft::progress_bar(health);

    ft::separator();
    ft::row({1.4f, 0.6f, 0.9f, 0.9f, 0.9f}, [&]() {
        ft::input("Server", app.server_ip, sizeof(app.server_ip));
        ft::input("Server port", app.server_port, sizeof(app.server_port), ft::InputFlags::CharsDecimal);
        if (ft::button("Refresh", ft::ColorRole::Accent)) {
            refresh_bots(app, true);
            append_log(app, "manual refresh completed");
        }
        if (ft::button("Connect all", ft::ColorRole::Accent)) {
            refresh_bots(app, true);
            send_to_all(app, "connect:" + command_target(app), "connect");
        }
        if (ft::button("Save")) {
            save_from_ui(app);
        }
    });

    ft::separator();
    ft::row({0.6f, 0.6f, 0.8f, 0.75f, 0.75f, 0.85f}, [&]() {
        ft::input("Instances", app.launch_count, sizeof(app.launch_count), ft::InputFlags::CharsDecimal);
        ft::input("Base port", app.port_base, sizeof(app.port_base), ft::InputFlags::CharsDecimal);
        ft::input("Username prefix", app.username_prefix, sizeof(app.username_prefix));
        ft::checkbox("Auto attach", &app.auto_attach_launched);
        ft::checkbox("Render off", &app.disable_rendering_on_start);
        if (ft::button("Launch fleet", ft::ColorRole::Accent)) {
            sync_scan_range_to_launch_range(app);
            launch_instances(app);
        }
    });

    ft::row(4, [&]() {
        if (ft::button("Kill launched", ft::ColorRole::Warning)) {
            kill_launched_instances(app);
        }
        if (ft::button("Scan launched")) {
            int base = parse_int(app.port_base, 8765, 1, 65535);
            int count = parse_int(app.launch_count, 1, 1, 64);
            int attached = scan_and_attach(app, "127.0.0.1", base, base + count - 1);
            append_log(app, "launched-port scan attached " + std::to_string(attached) + " bot(s)");
            ft::toast_success(("Attached " + std::to_string(attached) + " bot(s)").c_str());
        }
        if (ft::button("Stop all")) {
            send_to_all(app, "chat:#stop", "#stop");
        }
        if (ft::button("Toggle render all")) {
            send_to_all(app, "toggle_render", "toggle render");
        }
    });

    if (!app.launch_message.empty()) {
        std::string status = "Launch status: " + app.launch_message;
        ft::text_wrapped(status.c_str());
    }

    ft::separator();
    ft::row({1.1f, 0.7f, 0.8f, 1.0f}, [&]() {
        ft::input("Attach host", app.manual_host, sizeof(app.manual_host));
        ft::input("Attach port", app.manual_port, sizeof(app.manual_port), ft::InputFlags::CharsDecimal);
        if (ft::button("Attach bot")) {
            int port = parse_int(app.manual_port, 0, 1, 65535);
            int id = attach_bot(app, trim(app.manual_host), port, "");
            if (id > 0) {
                append_log(app, "attached bot " + std::to_string(id) + " at " +
                                trim(app.manual_host) + ":" + std::to_string(port));
                ft::toast_success("Bot attached");
            } else {
                ft::toast_error("Attach failed");
            }
        }
        ft::input("Scan host", app.scan_host, sizeof(app.scan_host));
    });

    ft::row({0.7f, 0.7f, 0.9f, 1.0f, 1.0f}, [&]() {
        ft::input("Start port", app.scan_start_port, sizeof(app.scan_start_port), ft::InputFlags::CharsDecimal);
        ft::input("End port", app.scan_end_port, sizeof(app.scan_end_port), ft::InputFlags::CharsDecimal);
        if (ft::button("Use launch range")) {
            sync_scan_range_to_launch_range(app);
            append_log(app, "scan range synced to launch ports");
        }
        if (ft::button("Scan range")) {
            int start = parse_int(app.scan_start_port, 0, 1, 65535);
            int end = parse_int(app.scan_end_port, start, 1, 65535);
            int count = scan_and_attach(app, trim(app.scan_host), start, end);
            append_log(app, "scan attached " + std::to_string(count) + " bot(s)");
            ft::toast_success(("Attached " + std::to_string(count) + " bot(s)").c_str());
        }
        if (ft::button("Attach base")) {
            int port = parse_int(app.port_base, 8765, 1, 65535);
            int id = attach_bot(app, "127.0.0.1", port, padded_username(app, 0));
            append_log(app, "base attach result id=" + std::to_string(id));
        }
    });

    ft::separator();
    ft::row({2.0f, 0.8f, 0.9f, 0.9f, 0.9f}, [&]() {
        ft::input("Broadcast", app.broadcast, sizeof(app.broadcast));
        if (ft::button("Send chat")) {
            std::string msg = trim(app.broadcast);
            if (!msg.empty()) {
                send_to_all(app, "chat:" + msg, "chat");
                app.broadcast[0] = '\0';
            }
        }
        if (ft::button("Send Baritone")) {
            std::string msg = trim(app.broadcast);
            if (!msg.empty()) {
                if (!msg.empty() && msg[0] == '#') msg.erase(0, 1);
                send_to_all(app, "baritone:" + msg, "baritone");
                app.broadcast[0] = '\0';
            }
        }
        if (ft::button("Send raw")) {
            std::string msg = trim(app.broadcast);
            if (!msg.empty()) {
                send_to_all(app, msg, "raw");
                app.broadcast[0] = '\0';
            }
        }
        if (ft::button("Clear")) {
            app.broadcast[0] = '\0';
        }
    });

    ft::separator();
    std::string endpoints = "Endpoints:";
    if (app.bots.empty()) {
        endpoints += "\n  none";
    } else {
        for (const Bot& bot : app.bots) {
            endpoints += "\n  " + bot.name + "  " + bot.host + ":" + std::to_string(bot.port) +
                         "  " + (bot.status.reachable ? "online" : "offline") +
                         "  " + (bot.status.connected ? "in-game" : "not-in-game");
            if (!bot.status.offline_username.empty()) endpoints += "  " + bot.status.offline_username;
        }
    }
    ft::log_view("Fleet endpoints", endpoints.c_str(), app.bots.empty() ? 4 : std::min(10, (int)app.bots.size() + 2),
                 ft::LogViewFlags::WordWrap);

    if (!app.launched.empty()) {
        std::string processes = "Launched processes:";
        for (const LaunchedProcess& proc : app.launched) {
            processes += "\n  ";
            processes += process_running(proc) ? "running  " : "exited   ";
            processes += proc.username + "  port=" + std::to_string(proc.port) +
                         "  pid=" + std::to_string(proc.pid);
        }
        ft::log_view("Process audit", processes.c_str(), std::min(10, (int)app.launched.size() + 2),
                     ft::LogViewFlags::WordWrap);
    }

    ft::separator();
    if (app.bots.empty()) {
        ft::text_wrapped("No attached bots.");
        ft::row(3, [&]() {
            if (ft::button("Launch fleet", ft::ColorRole::Accent)) {
                sync_scan_range_to_launch_range(app);
                launch_instances(app);
            }
            if (ft::button("Scan default ports")) {
                int base = parse_int(app.port_base, 8765, 1, 65535);
                int count = parse_int(app.launch_count, 1, 1, 64);
                int attached = scan_and_attach(app, "127.0.0.1", base, base + count - 1);
                append_log(app, "default scan attached " + std::to_string(attached) + " bot(s)");
            }
            if (ft::button("Attach base port")) {
                int port = parse_int(app.port_base, 8765, 1, 65535);
                int id = attach_bot(app, "127.0.0.1", port, padded_username(app, 0));
                append_log(app, "base attach result id=" + std::to_string(id));
            }
        });
    } else {
        for (size_t i = 0; i < app.bots.size();) {
            int id_before = app.bots[i].id;
            render_bot_card(app, app.bots[i]);
            if (i < app.bots.size() && app.bots[i].id == id_before) ++i;
        }
    }
}

static void render_instance(AppState& app, LaunchedProcess* instance, int slot, int port) {
    bool running = instance && process_running(*instance);
    std::string username = instance && (running || slot < 0) ? instance->username : padded_username(app, slot);
    std::string status = instance && !instance->exit_status.empty() ? instance->exit_status : "Stopped";
    if (running) {
        Bot* bot = find_bot_by_id(app, instance->bot_id);
        status = instance->stop_requested ? "Stopping" :
            (bot && bot->status.connected ? "In game" :
             bot && bot->status.ready ? "Ready" : instance->bot_id ? "Starting" : "Running");
    }
    if (instance && instance->container && running && !instance->container_ready && !instance->stop_requested) {
        status = "Preparing container";
    }
    if (instance && instance->restart_pending) status = "Restarting";

    const std::string id = "##instance-" + std::to_string(port);
    ft::row({2.6f, 1.0f, 1.0f, 1.0f, 1.0f}, [&]() {
        ft::text_wrapped((username + "  ·  " + std::to_string(port) + "  ·  " + status).c_str());
        if (running && !instance->stop_requested) {
            if (instance->container && instance->container_ready && ft::button(("View" + id).c_str())) {
                std::string error;
                if (!run_container_action(app, *instance, ContainerOperation::View, error)) app.launch_message = error;
            }
#ifndef _WIN32
            if (ft::button(("Stop" + id).c_str())) {
                std::string error;
                if (!request_stop_instance(app, *instance, false, error)) app.launch_message = error;
            }
#endif
            if (ft::button(("Restart" + id).c_str())) {
                std::string error;
                if (request_stop_instance(app, *instance, false, error)) instance->restart_pending = true;
                else app.launch_message = error;
            }
        }
        if (running) {
            if (ft::button(("Kill" + id).c_str(), ft::ColorRole::Warning)) {
                instance->restart_pending = false;
                std::string error;
                if (!request_stop_instance(app, *instance, true, error)) app.launch_message = error;
            }
        } else if ((!instance || !instance->restart_pending) && port <= 65535) {
            if (ft::button(("Start" + id).c_str(), ft::ColorRole::Accent)) {
                app.launch_message.clear();
                if (slot >= 0) {
                    sync_inputs_to_config(app);
                    launch_slot(app, slot);
                } else {
                    start_managed_instance(app, *instance);
                }
            }
        }
    });
}

static void render_launch_tab(AppState& app) {
    ft::row({0.65f, 0.65f, 0.8f, 0.8f}, [&]() {
        ft::input("Instances", app.launch_count, sizeof(app.launch_count), ft::InputFlags::CharsDecimal);
        ft::input("Base port", app.port_base, sizeof(app.port_base), ft::InputFlags::CharsDecimal);
        ft::input("Username prefix", app.username_prefix, sizeof(app.username_prefix));
        ft::input("Username start", app.username_start, sizeof(app.username_start), ft::InputFlags::CharsDecimal);
    });

#ifdef __linux__
    ft::checkbox("Podman checkpoints", &app.use_containers);
#endif
    ft::row(5, [&]() {
        ft::checkbox("Auto attach launched", &app.auto_attach_launched);
        ft::checkbox("Auto launch on start", &app.auto_launch_on_start);
        if (!app.use_containers) ft::checkbox("Warm at title screen", &app.warm_instances);
        if (ft::checkbox("Auto tile windows", &app.auto_tile_windows)) app.tiled_windows.clear();
        ft::checkbox("Disable render on start", &app.disable_rendering_on_start);
    });

    if (!app.use_containers) {
        const char* preset_names[k_launch_preset_count];
        for (int i = 0; i < k_launch_preset_count; ++i) {
            preset_names[i] = k_launch_presets[i].name;
        }
        if (app.launch_preset < 0 || app.launch_preset >= k_launch_preset_count) {
            app.launch_preset = 0;
        }

        ft::row({1.4f, 0.8f}, [&]() {
            ft::dropdown("Preset", preset_names, k_launch_preset_count, &app.launch_preset, k_launch_preset_count);
            if (ft::button("Use preset")) {
                copy_text(app.launch_command, sizeof(app.launch_command), k_launch_presets[app.launch_preset].command);
                append_log(app, std::string("applied launch preset: ") + k_launch_presets[app.launch_preset].name);
                ft::toast_success("Preset applied");
            }
        });
        ft::text_wrapped(k_launch_presets[app.launch_preset].note);

        ft::input("Working directory", app.launch_cwd, sizeof(app.launch_cwd));
        ft::text_area_ex("Minecraft launch command", app.launch_command, sizeof(app.launch_command), 6,
                          ft::TextAreaFlags::WordWrap);
    }
    if (!app.launch_message.empty()) {
        std::string status = "Launch status: " + app.launch_message;
        ft::text_wrapped(status.c_str());
    }

    ft::row(4, [&]() {
        if (ft::button("Launch instances", ft::ColorRole::Accent)) {
            launch_instances(app);
        }
        if (ft::button("Kill instances", ft::ColorRole::Warning)) {
            kill_launched_instances(app);
        }
        if (ft::button("Save settings")) {
            save_from_ui(app);
        }
        if (ft::button("Scan launched ports")) {
            int base = parse_int(app.port_base, 8765, 1, 65535);
            int count = parse_int(app.launch_count, 1, 1, 64);
            int attached = scan_and_attach(app, "127.0.0.1", base, base + count - 1);
            append_log(app, "launched-port scan attached " + std::to_string(attached) + " bot(s)");
        }
    });

    ft::separator();
    if (ft::button("Tile windows")) {
        if (tile_instances(app, true) == 0) {
            ft::toast_warning("No managed Minecraft windows found.");
        }
    }
    int base = parse_int(app.port_base, 8765, 1, 65535);
    int count = std::min(parse_int(app.launch_count, 1, 1, 64), 65536 - base);
    for (int slot = 0; slot < count; ++slot) {
        int port = base + slot;
        render_instance(app, instance_for_port(app, port), slot, port);
    }
    for (size_t index = 0; index < app.launched.size(); ++index) {
        auto& instance = app.launched[index];
        if (instance.port < base || instance.port >= base + count) {
            render_instance(app, &instance, -1, instance.port);
        }
    }
}

static void render_advanced_modules(AppState& app) {
    const fs::path permission_file = app.config_path.parent_path() / ".advanced-modules";
    std::string consent = trim(read_file(permission_file));
    if (ft::button("Advanced modules")) ft::open_modal("Advanced modules");
    ft::modal("Advanced modules", [&]() {
        ft::text_wrapped("Confirm you have permission to use BotFury automation on the server you join.");
        if (ft::button("Confirm movement only")) {
            std::ofstream file(permission_file, std::ios::trunc);
            file << "movement\n";
            if (file) ft::close_modal();
            else ft::toast_error("Could not save permission");
        }
        if (ft::button("Confirm with operator teleports")) {
            std::ofstream file(permission_file, std::ios::trunc);
            file << "teleport\n";
            if (file) ft::close_modal();
            else ft::toast_error("Could not save permission");
        }
        if (!consent.empty() && ft::button("Disable")) {
            std::error_code error;
            fs::remove(permission_file, error);
            if (!error) ft::close_modal();
            else ft::toast_error("Could not disable advanced modules");
        }
        if (ft::button("Cancel")) ft::close_modal();
    });
}

static void render_settings_tab(AppState& app) {
    render_advanced_modules(app);
    ft::row({1.0f, 1.0f, 1.0f}, [&]() {
        ft::input("Max bots", app.max_instances, sizeof(app.max_instances), ft::InputFlags::CharsDecimal);
        ft::input("Startup scan seconds", app.attach_scan_seconds, sizeof(app.attach_scan_seconds), ft::InputFlags::CharsDecimal);
        if (ft::button("Save config", ft::ColorRole::Accent)) save_from_ui(app);
    });

    std::string path_line = "Config: " + app.config_path.string();
    ft::text_wrapped(path_line.c_str());
    ft::separator();
    ft::log_view("Dashboard log", app.log.c_str(), 18,
                 ft::LogViewFlags::WordWrap | ft::LogViewFlags::AutoScrollBottom);
}

static void apply_cli_overrides(AppState& app, int argc, char** argv) {
    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i] ? argv[i] : "";
        if (arg == "--launch-bots") {
            app.auto_launch_on_start = true;
            app.config.auto_launch_on_start = true;
#ifdef __linux__
        } else if (arg == "--containers") {
            app.use_containers = true;
#endif
        } else if (arg == "--no-open") {
            // handled in main
        } else if (arg.rfind("--count=", 0) == 0) {
            copy_text(app.launch_count, sizeof(app.launch_count), arg.substr(8));
        } else if (arg.rfind("--port-base=", 0) == 0) {
            copy_text(app.port_base, sizeof(app.port_base), arg.substr(12));
        } else if (arg.rfind("--username-prefix=", 0) == 0) {
            copy_text(app.username_prefix, sizeof(app.username_prefix), arg.substr(18));
        }
    }
    sync_inputs_to_config(app);
}

static volatile std::sig_atomic_t shutdown_requested = 0;

static void request_shutdown(int) {
    shutdown_requested = 1;
}

static bool dashboard_request_allowed(int port) {
    auto& request = ftht::internal::ctx();
    std::string host = ftht::internal::header_value(request.req_headers, "host");
    if (host != "127.0.0.1:" + std::to_string(port) && host != "localhost:" + std::to_string(port)
            && !(port == 80 && (host == "127.0.0.1" || host == "localhost"))) return false;
    if (request.req_method == "GET") return request.req_query.empty();
    return request.req_method == "POST"
            && ftht::internal::header_value(request.req_headers, "origin") == "http://" + host;
}

int main(int argc, char** argv) {
    std::signal(SIGINT, request_shutdown);
    std::signal(SIGTERM, request_shutdown);
    AppState app;
    app.config_path = find_config_path(argc > 0 ? argv[0] : nullptr);
    app.dashboard_dir = find_dashboard_dir(argc > 0 ? argv[0] : nullptr, app.config_path);
    app.config = load_config(app.config_path);
    const std::string legacy_arguments = " {username} {port} {server_ip} {server_port} {disable_rendering}";
    const std::string old_shell = "sh botfury-launch.sh" + legacy_arguments;
    const std::string old_batch = "botfury-launch.bat" + legacy_arguments;
    const std::string new_shell = old_shell + " {auto_join}";
    const std::string new_batch = old_batch + " {auto_join}";
    if (app.config.launch_command.empty() || app.config.launch_command == old_shell
            || app.config.launch_command == old_batch || app.config.launch_command == new_shell
            || app.config.launch_command == new_batch) {
        app.config.launch_command = k_launch_presets[0].command;
    }
    if (app.config.launch_working_directory.empty()) {
        app.config.launch_working_directory = app.dashboard_dir.string();
    }
    sync_config_to_inputs(app);
    apply_cli_overrides(app, argc, argv);

    ft::Config cfg;
    cfg.title = "BotFury Dashboard";
    cfg.mode = ft::Mode::Auto;
    cfg.width = 1180;
    cfg.height = 780;
    cfg.web_host = "127.0.0.1";
    cfg.web_port = app.config.dashboard_port;
    cfg.web_client_poll_ms = 1500;
    cfg.web_dark_mode = true;
    cfg.web_extra_head_html =
        "<style>"
        "main{max-width:1380px}"
        ".ft-log{font-size:13px}"
        "</style>";

    bool open_browser = true;
    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i] ? argv[i] : "";
        if (arg == "--no-open") open_browser = false;
    }

    if (!ft::create_window(cfg, argc, argv)) {
        open_dashboard_url(app.config.dashboard_port);
        show_startup_error(app.config.dashboard_port);
        return 1;
    }

    bool serving_web = ft::active_mode() == ft::Mode::Web;
    if (open_browser && serving_web) {
        open_dashboard_url(app.config.dashboard_port);
    }

    if (app.auto_launch_on_start) {
        launch_instances(app);
    }

    static const char* tabs[] = {"Fleet", "Instances", "Settings"};
    while (!shutdown_requested) {
        bool pumped = serving_web ? ftht::pump(100) : ft::pump();
        poll_instances(app);
        if (!pumped) {
            if (!serving_web) break;
            continue;
        }
        if (ft::detail::render_mode() == ft::Mode::Web && !dashboard_request_allowed(cfg.web_port)) {
            ftht::begin();
            ftht::set_status(403, "Forbidden");
            ftht::text("Open BotFury from its local dashboard address.");
            ftht::end();
            continue;
        }

        refresh_bots(app);

        ft::begin();
        ft::text("BotFury");
        ft::separator();
        ft::tabs(tabs, 3, &app.tab);
        ft::separator();

        if (app.tab == 0) render_fleet_tab(app);
        else if (app.tab == 1) render_launch_tab(app);
        else render_settings_tab(app);

        ft::end();
    }

    for (ContainerTask& task : app.container_tasks) {
        if (task.operation == ContainerOperation::View) {
            std::string error;
            kill_launched_process(task.process, error);
        }
    }
    for (LaunchedProcess& instance : app.launched) {
        instance.restart_pending = false;
        if (!process_running(instance)) continue;
        std::string error;
        kill_launched_process(instance, error);
    }
    auto deadline = LaunchedProcess::Clock::now() + std::chrono::seconds(2);
    while (running_launched_count(app) && LaunchedProcess::Clock::now() < deadline) {
        for (LaunchedProcess& instance : app.launched) reap_launched_process(instance);
        poll_container_tasks(app);
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    ft::shutdown();
    return 0;
}
