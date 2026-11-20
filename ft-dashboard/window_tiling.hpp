#pragma once

#include "managed_process.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

#ifdef _WIN32
using ClientWindow = HWND;
#elif defined(__linux__)
#include <X11/Xatom.h>
#include <X11/Xlib.h>
#if __has_include(<X11/extensions/Xrandr.h>)
#include <X11/extensions/Xrandr.h>
#include <dlfcn.h>
#endif
using ClientWindow = Window;
#else
using ClientWindow = unsigned long;
#endif

struct WorkArea {
    int x = 0;
    int y = 0;
    int width = 0;
    int height = 0;

    bool operator==(const WorkArea& other) const {
        return x == other.x && y == other.y && width == other.width && height == other.height;
    }
};

struct TilingMonitor {
    WorkArea screen;
    WorkArea work;
    bool primary;
};

static WorkArea preferred_monitor_area(const std::vector<TilingMonitor>& monitors, WorkArea fallback) {
    if (monitors.empty()) return fallback;
    auto primary = std::find_if(monitors.begin(), monitors.end(), [](const TilingMonitor& monitor) {
        return monitor.primary;
    });
    if (primary == monitors.end()) primary = monitors.begin();

    auto selected = primary;
    int selected_overlap = 0;
    for (auto monitor = monitors.begin(); monitor != monitors.end(); ++monitor) {
        const WorkArea& screen = monitor->screen;
        if (screen.width <= 0 || screen.height <= 0 || screen.y + screen.height > primary->screen.y) continue;
        int overlap = std::min(screen.x + screen.width, primary->screen.x + primary->screen.width)
                    - std::max(screen.x, primary->screen.x);
        if (overlap > 0 && (selected == primary || screen.y < selected->screen.y
                || (screen.y == selected->screen.y && overlap > selected_overlap))) {
            selected = monitor;
            selected_overlap = overlap;
        }
    }
    return selected->work;
}

#if defined(_WIN32) || defined(__linux__)
static std::string bot_window_title(const LaunchedProcess& instance) {
    return "Bot " + std::to_string(instance.slot_number) + " - " + instance.username;
}
#endif

#ifdef _WIN32
static BOOL CALLBACK collect_tiling_monitor(HMONITOR monitor, HDC, LPRECT, LPARAM parameter) {
    MONITORINFO info{};
    info.cbSize = sizeof(info);
    if (!GetMonitorInfoW(monitor, &info)) return TRUE;
    auto& monitors = *reinterpret_cast<std::vector<TilingMonitor>*>(parameter);
    RECT screen = info.rcMonitor;
    RECT work = info.rcWork;
    monitors.push_back({{screen.left, screen.top, screen.right - screen.left, screen.bottom - screen.top},
                        {work.left, work.top, work.right - work.left, work.bottom - work.top},
                        (info.dwFlags & MONITORINFOF_PRIMARY) != 0});
    return TRUE;
}
static BOOL CALLBACK collect_client_window(HWND window, LPARAM parameter) {
    if (!IsWindowVisible(window) || GetWindow(window, GW_OWNER)) return TRUE;
    wchar_t class_name[32]{};
    GetClassNameW(window, class_name, 32);
    if (std::wstring(class_name) != L"GLFW30") return TRUE;
    auto& windows = *reinterpret_cast<std::vector<ClientWindow>*>(parameter);
    windows.push_back(window);
    return TRUE;
}
#elif defined(__linux__)
static std::vector<unsigned long> window_property(Display* display, Window window, const char* name, Atom type,
                                                   long count = 4096) {
    Atom found = 0;
    int format = 0;
    unsigned long length = 0;
    unsigned long remaining = 0;
    unsigned char* data = nullptr;
    std::vector<unsigned long> values;
    if (XGetWindowProperty(display, window, XInternAtom(display, name, False), 0, count, False, type,
                           &found, &format, &length, &remaining, &data) == 0
            && found == type && format == 32 && data) {
        auto* start = reinterpret_cast<unsigned long*>(data);
        values.assign(start, start + length);
    }
    if (data) XFree(data);
    return values;
}

static WorkArea linux_work_area(Display* display, Window root, WorkArea work) {
#if __has_include(<X11/extensions/Xrandr.h>)
    // Xlib stores extension callbacks on each open Display.
    static void* const library = dlopen("libXrandr.so.2", RTLD_LAZY | RTLD_LOCAL);
    if (!library) return work;
    auto get_monitors = reinterpret_cast<decltype(&XRRGetMonitors)>(dlsym(library, "XRRGetMonitors"));
    auto free_monitors = reinterpret_cast<decltype(&XRRFreeMonitors)>(dlsym(library, "XRRFreeMonitors"));
    int count = 0;
    XRRMonitorInfo* monitors = get_monitors && free_monitors ? get_monitors(display, root, True, &count) : nullptr;
    std::vector<TilingMonitor> areas;
    for (int index = 0; monitors && index < count; ++index) {
        const XRRMonitorInfo& monitor = monitors[index];
        WorkArea screen{monitor.x, monitor.y, monitor.width, monitor.height};
        if (screen.width <= 0 || screen.height <= 0) continue;
        int x = std::max(work.x, screen.x);
        int y = std::max(work.y, screen.y);
        int right = std::min(work.x + work.width, screen.x + screen.width);
        int bottom = std::min(work.y + work.height, screen.y + screen.height);
        WorkArea usable = right > x && bottom > y ? WorkArea{x, y, right - x, bottom - y} : screen;
        areas.push_back({screen, usable, monitor.primary != 0});
    }
    if (monitors) free_monitors(monitors);
    work = preferred_monitor_area(areas, work);
#else
    (void)display;
    (void)root;
#endif
    return work;
}

static void label_x11_window(Display* display, Window window, const LaunchedProcess& instance) {
    const auto label = bot_window_title(instance);
    const Atom name = XInternAtom(display, "_NET_WM_NAME", False);
    const Atom utf8 = XInternAtom(display, "UTF8_STRING", False);
    Atom found = 0;
    int format = 0;
    unsigned long length = 0;
    unsigned long remaining = 0;
    unsigned char* data = nullptr;
    int result = XGetWindowProperty(display, window, name, 0, 128, False, utf8,
                                     &found, &format, &length, &remaining, &data);
    bool unchanged = result == 0 && found == utf8 && format == 8 && data && remaining == 0
                  && std::string(reinterpret_cast<const char*>(data), length) == label;
    if (data) XFree(data);
    if (unchanged) return;
    XStoreName(display, window, label.c_str());
    XChangeProperty(display, window, name, utf8, 8, PropModeReplace,
                    reinterpret_cast<const unsigned char*>(label.data()), static_cast<int>(label.size()));
}

// A window may close between reading the desktop list and querying its PID.
static int ignore_closed_window(Display*, XErrorEvent*) {
    return 0;
}
#endif

static int tile_client_windows(const std::vector<const LaunchedProcess*>& instances, std::vector<ClientWindow>& previous,
                               WorkArea& previous_area, bool auto_tile, bool force) {
    WorkArea area;
    std::vector<ClientWindow> windows;
#ifdef _WIN32
    RECT rect{};
    if (!SystemParametersInfoW(SPI_GETWORKAREA, 0, &rect, 0)) return 0;
    area = {rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top};
    std::vector<TilingMonitor> monitors;
    EnumDisplayMonitors(nullptr, nullptr, collect_tiling_monitor, reinterpret_cast<LPARAM>(&monitors));
    area = preferred_monitor_area(monitors, area);
    std::vector<ClientWindow> candidates;
    EnumWindows(collect_client_window, reinterpret_cast<LPARAM>(&candidates));
    for (const auto& instance : instances) {
        if (!instance->job || instance->stop_requested) continue;
        for (HWND window : candidates) {
            DWORD pid = 0;
            GetWindowThreadProcessId(window, &pid);
            HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
            if (!process) continue;
            BOOL belongs = FALSE;
            IsProcessInJob(process, instance->job, &belongs);
            CloseHandle(process);
            if (belongs) {
                windows.push_back(window);
                auto label = bot_window_title(*instance);
                std::wstring title(label.begin(), label.end());
                wchar_t current[96]{};
                GetWindowTextW(window, current, 96);
                if (title != current) SetWindowTextW(window, title.c_str());
            }
        }
    }
#elif defined(__linux__)
    Display* display = XOpenDisplay(nullptr);
    if (!display) return 0;
    auto old_handler = XSetErrorHandler(ignore_closed_window);
    Window root = DefaultRootWindow(display);
    area = {0, 0, DisplayWidth(display, DefaultScreen(display)), DisplayHeight(display, DefaultScreen(display))};
    const auto desktop = window_property(display, root, "_NET_CURRENT_DESKTOP", XA_CARDINAL, 1);
    const auto work = window_property(display, root, "_NET_WORKAREA", XA_CARDINAL);
    if (!desktop.empty() && desktop[0] < work.size() / 4) {
        auto offset = desktop[0] * 4;
        area = {static_cast<int>(work[offset]), static_cast<int>(work[offset + 1]),
                static_cast<int>(work[offset + 2]), static_cast<int>(work[offset + 3])};
    }
    area = linux_work_area(display, root, area);
    auto candidates = window_property(display, root, "_NET_CLIENT_LIST", XA_WINDOW);
    for (Window window : candidates) {
        const auto pids = window_property(display, window, "_NET_WM_PID", XA_CARDINAL, 1);
        if (pids.empty() || pids[0] < 2 || pids[0] > 2147483647UL) continue;
        long group = getpgid(static_cast<pid_t>(pids[0]));
        if (group <= 0) continue;
        auto owner = std::find_if(instances.begin(), instances.end(), [group](const LaunchedProcess* instance) {
            return !instance->stop_requested && instance->pid == static_cast<unsigned long>(group);
        });
        if (owner != instances.end()) {
            windows.push_back(window);
            label_x11_window(display, window, **owner);
        }
    }
#else
    (void)instances;
#endif

    if ((force || (auto_tile && (windows != previous || !(area == previous_area)))) && !windows.empty() && area.width > 0 && area.height > 0) {
        int columns = static_cast<int>(std::ceil(std::sqrt(windows.size() * area.width / (1.55 * area.height))));
        columns = std::clamp(columns, 1, static_cast<int>(windows.size()));
        int rows = (static_cast<int>(windows.size()) + columns - 1) / columns;
        for (int index = 0; index < static_cast<int>(windows.size()); ++index) {
            int column = index % columns;
            int row = index / columns;
            int x = area.x + area.width * column / columns;
            int y = area.y + area.height * row / rows;
            int width = area.x + area.width * (column + 1) / columns - x;
            int height = area.y + area.height * (row + 1) / rows - y;
#ifdef _WIN32
            if (IsZoomed(windows[index]) || IsIconic(windows[index])) ShowWindowAsync(windows[index], SW_RESTORE);
            SetWindowPos(windows[index], nullptr, x, y, width, height, SWP_NOZORDER | SWP_NOACTIVATE | SWP_ASYNCWINDOWPOS);
#elif defined(__linux__)
            XEvent event{};
            event.xclient.type = ClientMessage;
            event.xclient.display = display;
            event.xclient.window = windows[index];
            event.xclient.message_type = XInternAtom(display, "_NET_MOVERESIZE_WINDOW", False);
            event.xclient.format = 32;
            event.xclient.data.l[0] = StaticGravity | (15L << 8) | (2L << 12);
            event.xclient.data.l[1] = x;
            event.xclient.data.l[2] = y;
            auto frame = window_property(display, windows[index], "_NET_FRAME_EXTENTS", XA_CARDINAL, 4);
            int sides = frame.size() == 4 ? static_cast<int>(frame[0] + frame[1]) : 0;
            int top_bottom = frame.size() == 4 ? static_cast<int>(frame[2] + frame[3]) : 0;
            event.xclient.data.l[3] = std::max(1, width - sides);
            event.xclient.data.l[4] = std::max(1, height - top_bottom);
            XSendEvent(display, root, False, SubstructureRedirectMask | SubstructureNotifyMask, &event);
#endif
        }
    }
    previous = std::move(windows);
    previous_area = area;
#if defined(__linux__) && !defined(_WIN32)
    XCloseDisplay(display);
    XSetErrorHandler(old_handler);
#endif
    return static_cast<int>(previous.size());
}
