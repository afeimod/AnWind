package com.anwind.apps

import com.anwind.apps.browser.BrowserApp
import com.anwind.apps.calculator.CalculatorApp
import com.anwind.apps.clock.ClockApp
import com.anwind.apps.filemanager.FileExplorerApp
import com.anwind.apps.imageviewer.ImageViewerApp
import com.anwind.apps.media.MediaPlayerApp
import com.anwind.apps.minesweeper.MinesweeperApp
import com.anwind.apps.notepad.NotepadApp
import com.anwind.apps.settings.SettingsApp
import com.anwind.apps.sysinfo.SysInfoApp
import com.anwind.apps.terminal.TerminalApp
import com.anwind.core.window.AppRegistry

/**
 * 应用启动注册：所有内置应用在此注册到全局 AppRegistry。
 *
 * 由 [com.anwind.AnWindApp.onCreate] 调用。
 */
object AppBootstrap {
    fun registerAll() {
        AppRegistry.register(BrowserApp)
        AppRegistry.register(FileExplorerApp)
        AppRegistry.register(SettingsApp)
        AppRegistry.register(NotepadApp)
        AppRegistry.register(CalculatorApp)
        AppRegistry.register(SysInfoApp)
        AppRegistry.register(ImageViewerApp)
        AppRegistry.register(ClockApp)
        AppRegistry.register(TerminalApp)
        AppRegistry.register(MediaPlayerApp)
        AppRegistry.register(MinesweeperApp)
    }
}
