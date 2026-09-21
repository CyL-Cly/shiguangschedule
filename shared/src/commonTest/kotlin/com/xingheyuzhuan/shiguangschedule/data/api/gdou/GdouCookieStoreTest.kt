package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GdouCookieStoreTest {
    @Test
    fun kbcxDoesNotSendSsoJsession() = runBlocking {
        val store = GdouCookieStore()
        store.restore(
            listOf(
                GdouCookie("JSESSIONID", "sso", "jw.gdou.edu.cn", "/sso"),
                GdouCookie("JSESSIONID", "root", "jw.gdou.edu.cn", "/"),
                GdouCookie("ADCCookie-x", "gw", "jw.gdou.edu.cn", "/")
            )
        )
        val sent = store.get(Url("https://jw.gdou.edu.cn/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151"))
        val ids = sent.filter { it.name == "JSESSIONID" }
        assertEquals(1, ids.size)
        assertEquals("root", ids.single().value)
        assertTrue(sent.any { it.name.startsWith("ADCCookie") })
    }

    @Test
    fun ssoPathKeepsSsoCookieFirst() = runBlocking {
        val store = GdouCookieStore()
        store.addCookie(
            Url("https://jw.gdou.edu.cn/sso/jziotlogin"),
            Cookie(name = "JSESSIONID", value = "sso", domain = "jw.gdou.edu.cn", path = "/sso")
        )
        store.addCookie(
            Url("https://jw.gdou.edu.cn/xtgl/index_initMenu.html"),
            Cookie(name = "JSESSIONID", value = "root", domain = "jw.gdou.edu.cn", path = "/")
        )
        val sso = store.get(Url("https://jw.gdou.edu.cn/sso/jziotlogin?ticket=ST-1"))
        val names = sso.filter { it.name == "JSESSIONID" }.map { it.value }
        assertEquals(listOf("sso"), names)
    }

    @Test
    fun wfwCookiesAreSentToGatewayUrls() = runBlocking {
        val store = GdouCookieStore()
        store.restore(
            listOf(GdouCookie("WFWSESSID", "s1", GDOU_WFW_HOST, "/"))
        )
        val sent = store.get(Url("$GDOU_WFW_GATEWAY_PREFIX/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151"))
        assertEquals(listOf("s1"), sent.filter { it.name == "WFWSESSID" }.map { it.value })
    }

    @Test
    fun wfwCookiesAreNotSentToOfficialJw() = runBlocking {
        val store = GdouCookieStore()
        store.restore(
            listOf(GdouCookie("WFWSESSID", "s1", GDOU_WFW_HOST, "/"))
        )
        val sent = store.get(Url("https://jw.gdou.edu.cn/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151"))
        assertTrue(sent.none { it.name == "WFWSESSID" })
    }

    @Test
    fun removeByNameCastgcKeepsJwJsession() = runBlocking {
        val store = GdouCookieStore()
        store.restore(
            listOf(
                GdouCookie("CASTGC", "ticket", "authserver.gdou.edu.cn", "/"),
                GdouCookie("JSESSIONID", "root", "jw.gdou.edu.cn", "/"),
                GdouCookie("route", "cas", "authserver.gdou.edu.cn", "/")
            )
        )
        store.removeByName("CASTGC")
        val cas = store.get(Url("https://authserver.gdou.edu.cn/authserver/login"))
        assertTrue(cas.none { it.name == "CASTGC" })
        assertEquals(listOf("cas"), cas.filter { it.name == "route" }.map { it.value })
        val jw = store.get(Url("https://jw.gdou.edu.cn/kbcx/xskbcx_cxXsgrkb.html"))
        assertEquals(listOf("root"), jw.filter { it.name == "JSESSIONID" }.map { it.value })
    }

    @Test
    fun removeMatchingDropsJwJsessionOnly() = runBlocking {
        val store = GdouCookieStore()
        store.restore(
            listOf(
                GdouCookie("CASTGC", "ticket", "authserver.gdou.edu.cn", "/"),
                GdouCookie("JSESSIONID", "sso", "jw.gdou.edu.cn", "/sso"),
                GdouCookie("JSESSIONID", "root", "jw.gdou.edu.cn", "/")
            )
        )
        store.removeMatching { cookie ->
            cookie.name.equals("JSESSIONID", ignoreCase = true) &&
                GdouHtml.isJwHost(cookie.domain.orEmpty())
        }
        val cas = store.get(Url("https://authserver.gdou.edu.cn/authserver/login"))
        assertEquals(listOf("ticket"), cas.filter { it.name == "CASTGC" }.map { it.value })
        val jw = store.get(Url("https://jw.gdou.edu.cn/kbcx/xskbcx_cxXsgrkb.html"))
        assertTrue(jw.none { it.name == "JSESSIONID" })
    }

    @Test
    fun authserverCookieIsNotSentToJwHosts() = runBlocking {
        val store = GdouCookieStore()
        store.restore(
            listOf(GdouCookie("CASTGC", "ticket", "authserver.gdou.edu.cn", "/"))
        )
        val official = store.get(Url("https://jw.gdou.edu.cn/xtgl/index_initMenu.html"))
        val gateway = store.get(Url("$GDOU_WFW_GATEWAY_PREFIX/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151"))
        assertTrue(official.none { it.name == "CASTGC" })
        assertTrue(gateway.none { it.name == "CASTGC" })
    }
}
