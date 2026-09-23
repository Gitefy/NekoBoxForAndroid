package io.nekohasekai.sagernet.fmt.vela

import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseVela(url: String): VelaBean {
    val link = url.replaceFirst("vela://", "https://").toHttpUrlOrNull()
        ?: error("invalid Vela link")
    return VelaBean().apply {
        name = link.fragment.orEmpty()
        serverAddress = link.host
        serverPort = link.port
        clientPrivateKey = link.queryParameter("client_key").orEmpty()
        serverPublicKey = link.queryParameter("server_key").orEmpty()
        validate()
    }
}

fun VelaBean.toUri(): String {
    validate()
    val builder = linkBuilder().host(serverAddress).port(serverPort)
        .addQueryParameter("client_key", clientPrivateKey)
        .addQueryParameter("server_key", serverPublicKey)
    if (name.isNotBlank()) builder.fragment(name)
    return builder.toLink("vela")
}
