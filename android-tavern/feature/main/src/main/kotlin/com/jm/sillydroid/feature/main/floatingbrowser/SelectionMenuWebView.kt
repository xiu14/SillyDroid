package com.jm.sillydroid.feature.main.floatingbrowser

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView

/**
 * 追加到 WebView 长按选字菜单里的一项；由当前 Activity 的宿主挂上、解绑时清空。
 *
 * [onClick] 处理完后必须调用传入的 done 才会关闭菜单：Chromium 关菜单时会清掉选区，
 * 宿主要先从页面里取到选中的文字，再让菜单关闭。
 */
data class SelectionMenuAction(val title: CharSequence, val onClick: (done: () -> Unit) -> Unit)

/**
 * 在 Chromium 的浮动选字菜单里追加宿主自己的菜单项。
 *
 * Chromium 选区变化时会在 onPrepareActionMode 里重建菜单，所以每次 prepare 后都检查一遍，
 * 缺了就补上；点击只拦截自己的 id，其余照常交给 Chromium。
 */
internal class SelectionMenuWebView(context: Context) : WebView(context) {
    var selectionMenuAction: SelectionMenuAction? = null

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        val action = selectionMenuAction
        if (callback == null || action == null || type != ActionMode.TYPE_FLOATING) {
            return super.startActionMode(callback, type)
        }
        return super.startActionMode(SelectionMenuCallback(callback, action), type)
    }

    private class SelectionMenuCallback(
        private val delegate: ActionMode.Callback,
        private val action: SelectionMenuAction
    ) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val created = delegate.onCreateActionMode(mode, menu)
            ensureItem(menu)
            return created
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val changed = delegate.onPrepareActionMode(mode, menu)
            return ensureItem(menu) || changed
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != ITEM_ID) {
                return delegate.onActionItemClicked(mode, item)
            }
            // 不能点完立刻 finish：取选区的 JS 是异步的，和 finish 触发的清选区谁先到渲染进程不确定。
            var finished = false
            action.onClick {
                if (!finished) {
                    finished = true
                    mode.finish()
                }
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            delegate.onDestroyActionMode(mode)
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (delegate is ActionMode.Callback2) {
                delegate.onGetContentRect(mode, view, outRect)
            } else {
                super.onGetContentRect(mode, view, outRect)
            }
        }

        private fun ensureItem(menu: Menu): Boolean {
            if (menu.findItem(ITEM_ID) != null) {
                return false
            }
            // order 取 0 排在复制等系统项前面，尽量不被挤进溢出菜单；负数会被当成非法分类直接抛异常。
            menu.add(Menu.NONE, ITEM_ID, ITEM_ORDER, action.title)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }
    }

    private companion object {
        const val ITEM_ID = 0x5D7A_0001
        const val ITEM_ORDER = 0
    }
}
