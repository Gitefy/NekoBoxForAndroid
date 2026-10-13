package moe.matsuri.nb4a.net

import android.net.DnsResolver
import android.os.CancellationSignal
import android.system.ErrnoException
import android.system.OsConstants
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import libcore.ExchangeContext
import libcore.LocalDNSTransport
import java.net.InetAddress

object LocalResolverImpl : LocalDNSTransport {

    // new local

    override fun raw(): Boolean = true

    override fun networkHandle(): Long =
        (SagerNet.underlyingNetwork ?: DefaultNetworkListener.findUnderlyingNetwork())?.networkHandle ?: 0

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val network = SagerNet.underlyingNetwork ?: DefaultNetworkListener.findUnderlyingNetwork()
        if (network == null) {
            ctx.errnoCode(OsConstants.ENETUNREACH)
            return
        }

        val signal = CancellationSignal()
        ctx.onCancel(signal::cancel)

        val callback = object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(answer: ByteArray, rcode: Int) {
                try {
                    ctx.rawSuccess(answer)
                } catch (e: Throwable) {
                    Logs.w(e)
                    ctx.errnoCode(OsConstants.EIO)
                }
            }

            override fun onError(error: DnsResolver.DnsException) {
                try {
                    val cause = error.cause
                    if (cause is ErrnoException) {
                        ctx.errnoCode(cause.errno)
                    } else {
                        Logs.w(error)
                        ctx.errnoCode(OsConstants.ECONNREFUSED)
                    }
                } catch (e: Throwable) {
                    Logs.w(e)
                    ctx.errnoCode(OsConstants.EIO)
                }
            }
        }

        try {
            DnsResolver.getInstance().rawQuery(
                network,
                message,
                DnsResolver.FLAG_NO_RETRY,
                Dispatchers.IO.asExecutor(),
                signal,
                callback
            )
        } catch (e: Throwable) {
            Logs.w(e)
            ctx.errnoCode(OsConstants.EIO)
        }
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val net = SagerNet.underlyingNetwork ?: DefaultNetworkListener.findUnderlyingNetwork()
        if (net == null) {
            ctx.errnoCode(OsConstants.ENETUNREACH)
            return
        }

        val signal = CancellationSignal()
        ctx.onCancel(signal::cancel)

        val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
            override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                try {
                    if (rcode == 0) {
                        ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                    } else {
                        ctx.errorCode(rcode)
                    }
                } catch (e: Throwable) {
                    Logs.w(e)
                    ctx.errnoCode(OsConstants.EIO)
                }
            }

            override fun onError(error: DnsResolver.DnsException) {
                try {
                    val cause = error.cause
                    if (cause is ErrnoException) {
                        ctx.errnoCode(cause.errno)
                    } else {
                        Logs.w(error)
                        ctx.errnoCode(OsConstants.ECONNREFUSED)
                    }
                } catch (e: Throwable) {
                    Logs.w(e)
                    ctx.errnoCode(OsConstants.EIO)
                }
            }
        }

        val type = when {
            network.endsWith("4") -> DnsResolver.TYPE_A
            network.endsWith("6") -> DnsResolver.TYPE_AAAA
            else -> null
        }
        try {
            if (type != null) {
                DnsResolver.getInstance().query(
                    net,
                    domain,
                    type,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    callback
                )
            } else {
                DnsResolver.getInstance().query(
                    net,
                    domain,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    callback
                )
            }
        } catch (e: Throwable) {
            Logs.w(e)
            ctx.errnoCode(OsConstants.EIO)
        }
    }

}
