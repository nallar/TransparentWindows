package nallar.transparentwindows

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser
import nallar.transparentwindows.jna.User32Fast
import nallar.transparentwindows.jna.WindowWrapper
import java.awt.*
import java.awt.event.ActionListener
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JOptionPane

object TransparentWindows {
	private val POLLING_MS = 100L
	private val mainThreadRun = AtomicReference<Runnable>()
	val forceFullTrans = 255
	private var exit: Boolean = false
	private var lastActive: HWND? = null
	private var mainThread: Thread? = null
	private var trayIcon: TrayIcon? = null
	var activeTrans = 225
	var foreInactiveTrans = 225
	var backTrans = 0

	fun debugPrint(s: String) {
		println(s)
	}

	private fun exit() {
		exit = true
		mainThread!!.interrupt()
		Thread.sleep(POLLING_MS * 2)

		clearTransparencies()
		if (trayIcon != null)
			SystemTray.getSystemTray().remove(trayIcon)
		System.exit(0)
	}

	private fun setForeInactiveTrans(s: String) {
		val t = s.toInt()
		runInMainThread(Runnable { foreInactiveTrans = t; })
	}

	private fun runInMainThread(r: Runnable) {
		val run = Runnable {
			clearTransparencies()
			r.run()
		}
		while (!mainThreadRun.compareAndSet(null, run)) {
			Thread.sleep(POLLING_MS)
		}
	}

	private fun setupSystemTray() {
		val tray = SystemTray.getSystemTray()
		val image = Toolkit.getDefaultToolkit().getImage(TransparentWindows::class.java.getResource("/icon.png"))
		val listener = ActionListener { e -> exit() }
		val popup = PopupMenu()
		val setForeInactiveTransItem = MenuItem("Set Inactive Transparency")
		setForeInactiveTransItem.addActionListener { setForeInactiveTrans(JOptionPane.showInputDialog("Set transparency level", foreInactiveTrans)) }
		popup.add(setForeInactiveTransItem)
		val defaultItem = MenuItem("Exit")
		defaultItem.addActionListener(listener)
		popup.add(defaultItem)
		trayIcon = TrayIcon(image, "Transparent Windows", popup)
		trayIcon!!.addActionListener(listener)
		trayIcon!!.isImageAutoSize = true
		tray.add(trayIcon!!)

		Runtime.getRuntime().addShutdownHook(object : Thread() {
			override fun start() {
				exit()
			}
		})
	}

	private fun clearTransparencies() {
		for (windowWrapper in windows) {
			windowWrapper.setAlpha(255)
		}

		taskBar?.setAlpha(foreInactiveTrans)
	}

	private fun setTransparencies(active: HWND?) {
		val windows = windows

		val area = RECT()

		for (w in windows) {
			val inner = w.rect!!
			area.left = Math.min(area.left, inner.left)
			area.top = Math.min(area.top, inner.top)
			area.right = Math.max(area.right, inner.right)
			area.bottom = Math.max(area.bottom, inner.bottom)
		}

		val windowOccluder = WindowOccluder(area)
		windowOccluder.occlude(windows)

		var activeWindowWrapper: WindowWrapper? = null

		if (active != null) {
			activeWindowWrapper = WindowWrapper(active)
			if (windows.remove(activeWindowWrapper)) {
				lastActive = active
			} else {
				activeWindowWrapper = null
			}
		}

		/*if (activeWindowWrapper == null && lastActive != null) {
            activeWindowWrapper = new WindowWrapper(lastActive);
            if (!windows.remove(activeWindowWrapper)) {
                activeWindowWrapper = null;
            }
        }*/

		if (activeWindowWrapper != null) {
			activeWindowWrapper.setAlpha(forceFullTrans)
		}

		for (windowWrapper in windows) {
			/*if (windowWrapper.title.contains(" - IntelliJ IDEA") || windowWrapper.title.contains(" - paint.net 4.")) {
                windowWrapper.visible = 2;
            }*/
			//if (windowWrapper.title.contains(" - Google Chrome")) {
			val style = User32Fast.GetWindowLongPtrA(windowWrapper.hwnd, WinUser.GWL_STYLE).toLong()
			// Check if it has a title bar
			if (style and 0x00C00000L != 0x00C00000L) {
				windowWrapper.visible = 3
			}

			if (windowWrapper.visible == 1 && style and 0x8L == 0x8L) {
				// it's an always on top window
				windowWrapper.visible = 2
			}

			windowWrapper.setAlpha(windowWrapper.alphaForVisibility())
		}

	}

	private fun isValidWindowForTransparency(hWnd: HWND, r: RECT, title: String): Boolean {
		if (title.contains("Planetside2 v")) {
			return false
		}

		val exe = User32Fast.GetWindowExe(hWnd)

		val exeLast = exe.substring(exe.lastIndexOf('\\') + 1)

		when (exeLast) {
			"Planetside2_x64.exe", "OBS.exe", "osu!.exe" -> return false
		}

		debugPrint("Title found " + title + " for " + hWnd + " of process " + User32Fast.GetWindowThreadProcessId(hWnd)
			+ " with exe " + exe)

		if (title.isEmpty() && exe == "C:\\Windows\\explorer.exe") {
			// Explorer-derp window?
			val height = r.bottom - r.top
			debugPrint("Found likely explorer derp: " + height)
			if (height > 900) {
				User32.INSTANCE.DestroyWindow(hWnd)
				User32.INSTANCE.CloseWindow(hWnd)
			}
		}

		when (title) {
			"", "Program Manager" -> {
				User32.INSTANCE.InvalidateRect(hWnd, null, true)
				val f = WinDef.DWORD((0x1 or 0x2 or 0x4 or 0x100 or 0x200 or 0x80).toLong())
				User32.INSTANCE.RedrawWindow(hWnd, null, null, f)

				return false
			}
		}
		return true
	}

	private val windows: MutableList<WindowWrapper>
		get() {
			val windows = ArrayList<WindowWrapper>()
			val order = ArrayList<HWND?>()
			var top: HWND? = User32Fast.GetTopWindow(HWND(Pointer(0)))!!
			var lastTop: HWND? = null
			while (top != lastTop) {
				lastTop = top
				order.add(top)
				top = User32Fast.GetWindow(top, User32.GW_HWNDNEXT)
			}
			User32Fast.EnumWindows(WinUser.WNDENUMPROC { hWnd, lParam ->
				if (User32Fast.IsWindowVisible(hWnd)) {
					val r = RECT()
					User32Fast.GetWindowRect(hWnd, r)
					if (r.left > -32000) {
						val buffer = ByteArray(1024)
						User32Fast.GetWindowTextA(hWnd, buffer, buffer.size)
						val title = Native.toString(buffer)
						if (!isValidWindowForTransparency(hWnd, r, title)) {
							return@WNDENUMPROC true
						}

						// workaround - rects oversized?
						val shrinkFactor = 20

						r.left = r.left + shrinkFactor
						r.top = r.top + shrinkFactor
						r.right = r.right - shrinkFactor
						r.bottom = r.bottom - shrinkFactor

						if (r.left > r.right) {
							r.left = r.right - 1
						}

						if (r.top > r.bottom) {
							r.top = r.bottom - 1
						}

						windows.add(WindowWrapper(hWnd, r, title))
					}
				}
				true
			}, Pointer(0))
			Collections.sort(windows) { o1, o2 -> order.indexOf(o2.hwnd) - order.indexOf(o1.hwnd) }
			return windows
		}

	@JvmStatic fun main(args: Array<String>) {
		mainThread = object : Thread() {
			internal var lastActive: HWND? = null

			override fun run() {
				while (true) {
					try {
						Thread.sleep(POLLING_MS)
					} catch (e: InterruptedException) {
						if (exit)
							return
					}

					mainThreadRun.getAndSet(null)?.run()

					val active = User32Fast.GetForegroundWindow()
					if (active == lastActive) {
						continue
					}
					if (exit)
						return
					lastActive = active
					setTransparencies(active)
				}
			}
		}
		taskBar?.setAlpha(activeTrans)

		mainThread!!.start()
		setupSystemTray()
	}

	private val taskBar: WindowWrapper?
		get() {
			return WindowWrapper(User32Fast.FindWindowA("Shell_TrayWnd", null) ?: return null)
		}

	private class WindowOccluder internal constructor(area: RECT) {
		private val screen: ShortArray
		private val width: Int
		private val height: Int
		private val xOffset: Int
		private val yOffset: Int

		init {
			debugPrint("Area " + area)
			xOffset = -area.left
			yOffset = -area.top
			width = area.right - area.left
			height = area.bottom - area.top
			screen = ShortArray((area.bottom - area.top) * (area.right - area.left))
		}

		private fun lookup(x: Int, y: Int): Int {
			return (y + yOffset) * width + x + xOffset
		}

		internal fun occlude(windows: List<WindowWrapper>) {
			if (windows.size >= 254) {
				throw IllegalArgumentException("More than 253 windows not supported")
			}
			for (id in windows.indices) {
				debugPrint("occlude " + id + " is " + windows[id])
				val windowWrapper = windows[id]
				occlude(windowWrapper, (id + 1).toShort())
			}
			val visibleSet = BitSet()
			for (id in screen) {
				if (id.toInt() != 0) {
					visibleSet.set(id.toInt())
				}
			}

			var i = visibleSet.nextSetBit(0)
			while (i != -1) {
				if (i == 0) {
					i = visibleSet.nextSetBit(i + 1)
					continue
				}

				windows[i - 1].visible = 1
				debugPrint("occlude " + (i - 1) + " visible " + windows[i - 1])
				i = visibleSet.nextSetBit(i + 1)
			}
		}

		internal fun occlude(w: WindowWrapper, id: Short) {
			val r = w.rect!!
			for (y in r.top..r.bottom - 1) {
				for (x in r.left..r.right - 1) {
					screen[lookup(x, y)] = id
				}
			}
		}
	}

}
