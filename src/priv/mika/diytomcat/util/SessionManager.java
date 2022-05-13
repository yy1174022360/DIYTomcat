package priv.mika.diytomcat.util;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.SecureUtil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import priv.mika.diytomcat.http.Request;
import priv.mika.diytomcat.http.Response;
import priv.mika.diytomcat.http.StandardSession;

import javax.servlet.ServletContext;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    // 所有的session都放在这里
    private static Map<String, StandardSession> sessionMap = new ConcurrentHashMap<>();
    // session的默认失效时间
    private static int defaultTimeout = getTimeout();

    /**
     * 默认启动检测Session是否失效的线程
     */
    static {
        startSessionOutdateCheckThread();
    }
    /**
     * 从web.xml 中获取默认失效时间
     */
    private static int getTimeout() {
        int defaultResult = 30;
        try {
            Document document = Jsoup.parse(Constant.webXmlFile, "utf-8");
            Elements elements = document.select("session-config session-timeout");
            if (elements.isEmpty()) {
                return defaultResult;
            }
            return Convert.toInt(elements.get(0).text());
        } catch (IOException e) {
            return defaultResult;
        }
    }
    /**
     * 默认启动检测Session是否失效的线程
     */
    private static void startSessionOutdateCheckThread() {
        new Thread() {
            public void run() {
                while (true) {
                    checkOutDateSession();
                    ThreadUtil.sleep(1000 * 30);
                }
            }
        }.start();
    }
    /**
     * 从sessionMap里根据 lastAccessedTime 筛选出过期的 jsessionids ,然后把他们从 sessionMap 里去掉
     */
    private static void checkOutDateSession() {
        Set<String> jsessionids = sessionMap.keySet();
        List<String> outdateJessionIds = new ArrayList<>();
        for (String jsessionid : jsessionids) {
            StandardSession session = sessionMap.get(jsessionid);
            long interval = System.currentTimeMillis() - session.getLastAccessedTime();
            if (interval > session.getMaxInactiveInterval() * 1000 * 60) {
                outdateJessionIds.add(jsessionid);
            }
        }
        for (String jsessionid : outdateJessionIds) {
            sessionMap.remove(jsessionid);
        }
    }
    /**
     * 创建 sessionid
     */
    public static synchronized String generateSessionId() {
        String result = null;
        byte[] bytes = RandomUtil.randomBytes(16);
        result = new String(bytes);
        result = SecureUtil.md5(result);
        result = result.toUpperCase();
        return result;
    }
    /**
     * 这是获取session的主逻辑
     * 如果浏览器没有传递 jsessionid 过来，那么就创建一个新的session
     * 如果浏览器传递过来的 jsessionid 无效，那么也创建一个新的 sessionid
     * 否则就使用现成的session, 并且修改它的lastAccessedTime， 以及创建对应的 cookie
     */
    public static HttpSession getSession(String jsessionid, Request request, Response response) {
        if (null == jsessionid) {
            return newSession(request, response);
        } else {
            StandardSession currentSession = sessionMap.get(jsessionid);
            if (null == currentSession) {
                return newSession(request, response);
            } else {
                currentSession.setLastAccessedTime(System.currentTimeMillis());
                createCookieBySession(currentSession, request, response);
                return currentSession;
            }
        }
    }

    private static HttpSession newSession(Request request, Response response) {
        ServletContext servletContext = request.getServletContext();
        String sessionId = generateSessionId();
        StandardSession session = new StandardSession(sessionId, servletContext);
        session.setMaxInactiveInterval(defaultTimeout);
        session.setLastAccessedTime(System.currentTimeMillis());
        sessionMap.put(sessionId, session);
        createCookieBySession(session, request, response);
        return session;
    }
    /**
     * 用session创建cookie
     */
    private static void createCookieBySession(HttpSession session, Request request, Response response) {
        Cookie cookie = new Cookie("JSESSIONID", session.getId());
        cookie.setMaxAge(session.getMaxInactiveInterval());
        cookie.setPath(request.getContext().getPath());
        response.addCookie(cookie);
    }

}
