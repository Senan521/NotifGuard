package com.notifguard

import android.app.NotificationChannel
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.Locale
import kotlin.coroutines.resume

/* ---------- Kurallar ---------- */
object Rules {
    const val DEF_BLOCK = "indirim,kampanya,firsat,kupon,promo,promotion,discount,sale,deal,offer,marketing,reklam,newsletter,bulten,oneri,recommend,anket,survey,hediye,cekilis,flash,sepetinde"
    const val DEF_ALLOW = "siparis,order,kargo,shipping,delivery,teslim,odeme,payment,guvenlik,security,dogrulama,otp,verification,iade,refund,fatura,invoice"

    private fun p(c: Context) = c.getSharedPreferences("rules", Context.MODE_PRIVATE)
    fun block(c: Context) = p(c).getString("block", DEF_BLOCK)!!
    fun allow(c: Context) = p(c).getString("allow", DEF_ALLOW)!!
    fun save(c: Context, b: String, a: String) = p(c).edit().putString("block", b).putString("allow", a).apply()
    fun mode(c: Context) = p(c).getInt("mode", 0)
    fun setMode(c: Context, m: Int) = p(c).edit().putInt("mode", m).apply()
    fun filter(c: Context) = p(c).getBoolean("filter", true)
    fun setFilter(c: Context, v: Boolean) = p(c).edit().putBoolean("filter", v).apply()
    fun count(c: Context) = p(c).getInt("count", 0)
    fun inc(c: Context) = p(c).edit().putInt("count", count(c) + 1).apply()

    fun norm(s: String) = s.lowercase(Locale.ROOT).replace("\u0307", "")
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')

    private fun words(s: String) = s.split(",").map { norm(it.trim()) }.filter { it.isNotEmpty() }

    /** Engelleme kelimesi eşleşmesi; kanal taramasında öneri üretmek için kullanılır. */
    fun matchesBlock(c: Context, text: String): Boolean {
        val n = norm(text)
        return words(block(c)).any { n.contains(it) }
    }

    /** İzin listesi her zaman kazanır. */
    fun isJunk(c: Context, text: String): Boolean {
        val n = norm(text)
        if (words(allow(c)).any { n.contains(it) }) return false
        return matchesBlock(c, text)
    }
}

/* ---------- Kanal işlemleri (gizli API, yetkili süreçte çalışır) ---------- */
data class Ch(val id: String, val name: String, val importance: Int)

object ChannelOps {
    private val iface by lazy { Class.forName("android.app.INotificationManager") }

    private fun nm(b: IBinder): Any {
        HiddenApiBypass.addHiddenApiExemptions("L")
        return Class.forName("android.app.INotificationManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, b)!!
    }

    @Suppress("UNCHECKED_CAST")
    private fun raw(b: IBinder, pkg: String, uid: Int): List<NotificationChannel> {
        val n = nm(b)
        val m = iface.methods.first { it.name == "getNotificationChannelsForPackage" }
        val res = if (m.parameterCount == 3) m.invoke(n, pkg, uid, false) else m.invoke(n, pkg, uid)
        return res!!.javaClass.getMethod("getList").invoke(res) as List<NotificationChannel>
    }

    fun list(b: IBinder, pkg: String, uid: Int): List<Ch> = try {
        raw(b, pkg, uid).map { Ch(it.id, it.name?.toString() ?: "", it.importance) }
    } catch (t: Throwable) { emptyList() }

    fun block(b: IBinder, pkg: String, uid: Int, id: String): Boolean = try {
        val ch = raw(b, pkg, uid).first { it.id == id }
        ch.importance = 0
        iface.methods.first { it.name == "updateNotificationChannelForPackage" }.invoke(nm(b), pkg, uid, ch)
        true
    } catch (t: Throwable) { false }
}

/* ---------- Backend'ler ---------- */
interface Backend {
    suspend fun channels(pkg: String, uid: Int): List<Ch>
    suspend fun block(pkg: String, uid: Int, id: String): Boolean
}

object ShizukuBackend : Backend {
    private val b by lazy { ShizukuBinderWrapper(SystemServiceHelper.getSystemService("notification")) }
    override suspend fun channels(pkg: String, uid: Int) = withContext(Dispatchers.IO) { ChannelOps.list(b, pkg, uid) }
    override suspend fun block(pkg: String, uid: Int, id: String) = withContext(Dispatchers.IO) { ChannelOps.block(b, pkg, uid, id) }
}

/** Root süreçte çalışır; Binder üzerinden iki komut: 1=listele, 2=kapat */
class RootSvc : RootService() {
    override fun onBind(intent: Intent): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            HiddenApiBypass.addHiddenApiExemptions("L")
            val pkg = data.readString() ?: ""
            val uid = data.readInt()
            val nb = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "notification") as IBinder
            if (code == 1) {
                val s = ChannelOps.list(nb, pkg, uid).joinToString("\n") {
                    "${it.id.replace("\t", " ")}\t${it.name.replace("\t", " ").replace("\n", " ")}\t${it.importance}"
                }
                reply?.writeString(s)
            } else {
                val ok = ChannelOps.block(nb, pkg, uid, data.readString() ?: "")
                reply?.writeInt(if (ok) 1 else 0)
            }
            return true
        }
    }
}

object RootBackend : Backend {
    private var binder: IBinder? = null

    suspend fun connect(ctx: Context): Backend? {
        if (binder != null) return this
        val root = withContext(Dispatchers.IO) { Shell.getShell().isRoot }
        if (!root) return null
        binder = withContext(Dispatchers.Main) {
            withTimeoutOrNull(8000) {
                suspendCancellableCoroutine<IBinder> { c ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(n: ComponentName, s: IBinder) { if (c.isActive) c.resume(s) }
                        override fun onServiceDisconnected(n: ComponentName) { binder = null }
                    }
                    RootService.bind(Intent(ctx.applicationContext, RootSvc::class.java), conn)
                }
            }
        }
        return if (binder != null) this else null
    }

    override suspend fun channels(pkg: String, uid: Int): List<Ch> = withContext(Dispatchers.IO) {
        val d = Parcel.obtain(); val r = Parcel.obtain()
        try {
            d.writeString(pkg); d.writeInt(uid)
            binder!!.transact(1, d, r, 0)
            r.readString().orEmpty().lines().filter { it.isNotBlank() }.mapNotNull {
                val p = it.split("\t")
                if (p.size == 3) Ch(p[0], p[1], p[2].toIntOrNull() ?: 3) else null
            }
        } catch (t: Throwable) { emptyList() } finally { d.recycle(); r.recycle() }
    }

    override suspend fun block(pkg: String, uid: Int, id: String): Boolean = withContext(Dispatchers.IO) {
        val d = Parcel.obtain(); val r = Parcel.obtain()
        try {
            d.writeString(pkg); d.writeInt(uid); d.writeString(id)
            binder!!.transact(2, d, r, 0)
            r.readInt() == 1
        } catch (t: Throwable) { false } finally { d.recycle(); r.recycle() }
    }
}

object Backends {
    suspend fun get(ctx: Context, mode: Int): Backend? = when (mode) {
        1 -> if (runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)) ShizukuBackend else null
        2 -> RootBackend.connect(ctx)
        else -> null
    }
}
