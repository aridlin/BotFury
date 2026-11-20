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
};

#ifdef _WIN32
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

static WorkArea primary_work_area(Display* display, Window root, WorkArea work) {
#if __has_include(<X11/extensions/Xrandr.h>)
    // Xlib stores extension callbacks on each open Display.
    static void* const library = dlopen("libXrandr.so.2", RTLD_LAZY | RTLD_LOCAL);
    if (!library) return work;
    auto get_monitors = reinterpret_cast<decltype(&XRRGetMonitors)>(dlsym(library, "XRRGetMonitors"));
    auto free_monitors = reinterpret_cast<decltype(&XRRFreeMonitors)>(dlsym(library, "XRRFreeMonitors"));
    int count = 0;
    XRRMonitorInfo* monitors = get_monitors && free_monitors ? get_monitors(display, root, True, &count) : nullptr;
    if (monitors && count > 0) {
        const XRRMonitorInfo* end = monitors + count;
        auto primary = std::find_if(static_cast<const XRRMonitorInfo*>(monitors), end,
                                    [](const XRRMonitorInfo& monitor) { return monitor.primary; });
        if (primary == end) primary = monitors;
        int x = std::max(work.x, primary->x);
        int y = std::max(work.y, primary->y);
        int right = std::min(work.x + work.width, primary->x + primary->width);
        int bottom = std::min(work.y + work.height, primary->y + primary->height);
        if (right > x && bottom > y) work = {x, y, right - x, bottom - y};
    }
    if (monitors) free_monitors(monitors);
#else
    (void)display;
    (void)root;
#endif
    return work;
}

// A window may close between reading the desktop list and querying its PID.
static int ignore_closed_window(Display*, XErrorEvent*) {
    return 0;
}
#endif

static int tile_client_windows(const std::vector<const LaunchedProcess*>& instances, std::vector<ClientWindow>& previous,
                               bool force) {
    WorkArea area;
    std::vector<ClientWindow> windows;
#ifdef _WIN32
    RECT rect{};
    if (!SystemParametersInfoW(SPI_GETWORKAREA, 0, &rect, 0)) return 0;
    area = {rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top};
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
            if (belongs) windows.push_back(window);
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
    area = primary_work_area(display, root, area);
    auto candidates = window_property(display, root, "_NET_CLIENT_LIST", XA_WINDOW);
    for (Window window : candidates) {
        const auto pids = window_property(display, window, "_NET_WM_PID", XA_CARDINAL, 1);
        if (pids.empty() || pids[0] < 2 || pids[0] > 2147483647UL) continue;
        long group = getpgid(static_cast<pid_t>(pids[0]));
        if (group <= 0) continue;
        auto owner = std::find_if(instances.begin(), instances.end(), [group](const LaunchedProcess* instance) {
            return !instance->stop_requested && instance->pid == static_cast<unsigned long>(group);
        });
        if (owner != instances.end()) windows.push_back(window);
    }
#else
    (void)instances;
#endif

    if ((force || windows != previous) && !windows.empty() && area.width > 0 && area.height > 0) {
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
#if defined(__linux__) && !defined(_WIN32)
    XCloseDisplay(display);
    XSetErrorHandler(old_handler);
#endif
    return static_cast<int>(previous.size());
}
