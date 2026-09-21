package com.xingheyuzhuan.shiguangschedule.data.api.gdou

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GdouHtmlTest {
    @Test
    fun hostOf_stripsPortAndScheme() {
        assertEquals(GDOU_JW_HOST, GdouHtml.hostOf("https://jw.gdou.edu.cn/xtgl/index_initMenu.html"))
        assertEquals(GDOU_WFW_HOST, GdouHtml.hostOf("$GDOU_WFW_GATEWAY_PREFIX/kbcx/xskbcx_cxXsgrkb.html"))
        assertEquals(GDOU_AUTH_HOST, GdouHtml.hostOf("https://authserver.gdou.edu.cn/authserver/login"))
        assertEquals("", GdouHtml.hostOf("/sso/jziotlogin"))
    }

    @Test
    fun isJwUrl_matchesOfficialAndGatewayUrls() {
        assertTrue(GdouHtml.isJwUrl("https://jw.gdou.edu.cn/kbcx/xskbcx_cxXsgrkb.html"))
        assertTrue(GdouHtml.isJwUrl("$GDOU_WFW_GATEWAY_PREFIX/kbcx/xskbcx_cxXsgrkb.html"))
        assertFalse(GdouHtml.isJwUrl("$GDOU_WFW_ORIGIN/"))
        assertFalse(GdouHtml.isJwUrl("https://authserver.gdou.edu.cn/authserver/login"))
        assertFalse(GdouHtml.isJwUrl("https://example.com/"))
        assertFalse(
            GdouHtml.isJwUrl("https://example.com/?next=https://jw.gdou.edu.cn/sso/jziotlogin")
        )
        assertFalse(GdouHtml.isJwUrl("https://jw.gdou.edu.cn.evil.com/sso"))
    }

    @Test
    fun isGatewayJwUrl_matchesGatewayEmbeddedJwPathsOnly() {
        assertTrue(GdouHtml.isGatewayJwUrl("$GDOU_WFW_GATEWAY_PREFIX/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151"))
        assertFalse(GdouHtml.isGatewayJwUrl("$GDOU_WFW_ORIGIN/xtgl/index_initMenu.html"))
        assertFalse(GdouHtml.isGatewayJwUrl("$GDOU_WFW_ORIGIN/https://other.com/x"))
        assertFalse(GdouHtml.isGatewayJwUrl("https://jw.gdou.edu.cn/kbcx/xskbcx_cxXsgrkb.html"))
    }

    @Test
    fun isOfficialJwUrl_onlyOfficialHost() {
        assertTrue(GdouHtml.isOfficialJwUrl("https://jw.gdou.edu.cn/sso/jziotlogin"))
        assertTrue(GdouHtml.isOfficialJwUrl("http://jw.gdou.edu.cn"))
        assertFalse(GdouHtml.isOfficialJwUrl("$GDOU_WFW_GATEWAY_PREFIX/kbcx/xskbcx_cxXsgrkb.html"))
        assertFalse(GdouHtml.isOfficialJwUrl("https://authserver.gdou.edu.cn/authserver/login"))
    }

    @Test
    fun isJwLoggedIn_acceptsGatewayMenuAndRejectsSso() {
        val menuHtml = """<script>var sessionUserKey = "u1";</script><div id="cdNav"></div>"""
        assertTrue(
            GdouHtml.isJwLoggedIn("$GDOU_WFW_GATEWAY_PREFIX/xtgl/index_initMenu.html?jsdm=xs", menuHtml)
        )
        assertTrue(GdouHtml.isJwLoggedIn("https://jw.gdou.edu.cn/xtgl/index_initMenu.html?jsdm=xs", menuHtml))
        assertFalse(GdouHtml.isJwLoggedIn("$GDOU_WFW_GATEWAY_PREFIX/sso/jziotlogin?ticket=ST-1", menuHtml))
        assertFalse(GdouHtml.isJwLoggedIn("https://authserver.gdou.edu.cn/authserver/login", menuHtml))
    }

    @Test
    fun isJwLoggedIn_rejectsEmptyBodyAndLoginForm() {
        val menu = "https://jw.gdou.edu.cn/xtgl/index_initMenu.html?jsdm=xs"
        assertFalse(GdouHtml.isJwLoggedIn(menu))
        assertFalse(GdouHtml.isJwLoggedIn(menu, "   "))
        assertFalse(GdouHtml.isJwLoggedIn(menu, """<input id="pwdEncryptSalt" value="abc"/>"""))
        assertFalse(GdouHtml.isJwLoggedIn(menu, "<html>gateway timeout</html>"))
        assertTrue(
            GdouHtml.isJwLoggedIn(
                "$GDOU_WFW_GATEWAY_PREFIX/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151",
                "<div>xskbcx</div>"
            )
        )
    }

    @Test
    fun rewriteOfficialJwToGateway_keepsPathAndQuery() {
        assertEquals(
            "$GDOU_WFW_ORIGIN/https://jw.gdou.edu.cn/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default",
            GdouHtml.rewriteOfficialJwToGateway(GDOU_KB_INDEX)
        )
        assertEquals(
            "$GDOU_WFW_ORIGIN/https://jw.gdou.edu.cn",
            GdouHtml.rewriteOfficialJwToGateway("https://jw.gdou.edu.cn")
        )
        assertEquals(
            "$GDOU_WFW_ORIGIN/https://jw.gdou.edu.cn/xtgl/index.html",
            GdouHtml.rewriteOfficialJwToGateway("http://jw.gdou.edu.cn/xtgl/index.html")
        )
        val auth = "https://authserver.gdou.edu.cn/authserver/login?service=https://jw.gdou.edu.cn/sso/jziotlogin"
        assertEquals(auth, GdouHtml.rewriteOfficialJwToGateway(auth))
        val already = "$GDOU_WFW_GATEWAY_PREFIX/kbcx/xskbcx_cxXsgrkb.html"
        assertEquals(already, GdouHtml.rewriteOfficialJwToGateway(already))
        assertEquals(
            "https://jw.gdou.edu.cn.evil.com/sso",
            GdouHtml.rewriteOfficialJwToGateway("https://jw.gdou.edu.cn.evil.com/sso")
        )
    }

    @Test
    fun forceHttps_upgradesOfficialAndGateway() {
        assertEquals(
            "https://jw.gdou.edu.cn/xtgl/index.html",
            GdouHtml.forceHttps("http://jw.gdou.edu.cn/xtgl/index.html")
        )
        assertEquals(
            "https://authserver.gdou.edu.cn/authserver/login",
            GdouHtml.forceHttps("http://authserver.gdou.edu.cn/authserver/login")
        )
        assertEquals(
            "$GDOU_WFW_ORIGIN/xtgl/index.html",
            GdouHtml.forceHttps("http://$GDOU_WFW_HOST/xtgl/index.html")
        )
    }

    @Test
    fun resolveUrl_resolvesRelativeWithinGateway() {
        assertEquals(
            "$GDOU_WFW_GATEWAY_PREFIX/kbcx/b.html",
            GdouHtml.resolveUrl("$GDOU_WFW_GATEWAY_PREFIX/kbcx/a.html", "b.html")
        )
    }

    @Test
    fun resolveUrl_rootRelativeKeepsGatewayEmbedPrefix() {
        assertEquals(
            "$GDOU_WFW_ORIGIN/https://jw.gdou.edu.cn/sso/jziotlogin",
            GdouHtml.resolveUrl("$GDOU_WFW_GATEWAY_PREFIX/kbcx/a.html", "/sso/jziotlogin")
        )
        assertEquals(
            "$GDOU_WFW_GATEWAY_PREFIX/xtgl/index_initMenu.html?jsdm=xs",
            GdouHtml.resolveUrl("$GDOU_WFW_GATEWAY_PREFIX/kbcx/a.html?gnmkdm=N2151", "/xtgl/index_initMenu.html?jsdm=xs")
        )
    }

    @Test
    fun resolveUrl_rootRelativeStaysWithinOwnOriginForNonGatewayBase() {
        assertEquals(
            "$GDOU_WFW_ORIGIN/login",
            GdouHtml.resolveUrl("$GDOU_WFW_ORIGIN/xtgl/index.html", "/login")
        )
        assertEquals(
            "$GDOU_JW_BASE/sso/jziotlogin",
            GdouHtml.resolveUrl("$GDOU_JW_BASE/xtgl/index.html", "/sso/jziotlogin")
        )
    }
}
