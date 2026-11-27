package com.ftbqbridge.neoforge.client;

import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWin32;

/** Manipulates only this client's own window, never another application's input/focus. */
final class ClientWindow {
    private ClientWindow() {}

    static void restore(long window) {
        if (Platform.isWindows()) {
            User32.INSTANCE.ShowWindow(handle(window), WinUser.SW_SHOWNOACTIVATE);
        } else {
            GLFW.glfwRestoreWindow(window);
        }
    }

    static void minimize(long window) {
        if (Platform.isWindows()) {
            User32.INSTANCE.ShowWindow(handle(window), WinUser.SW_SHOWMINNOACTIVE);
        } else {
            GLFW.glfwIconifyWindow(window);
        }
    }

    private static HWND handle(long window) {
        return new HWND(Pointer.createConstant(GLFWNativeWin32.glfwGetWin32Window(window)));
    }
}
