package org.epic.debug.cgi;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;

public class TestCgiServer {
    
    public static void testJetty() throws Exception {
        CGIProxy proxy = new CGIProxy(null, "foo");
        synchronized (proxy) {
            proxy.startListening();
            org.eclipse.jetty.xml.XmlConfiguration.main(new String[] {"c:/var/cgi/jetty.xml"});
            proxy.wait();
        }
        System.out.println("main finished");
    }
    
    public static void main(String[] args) throws Exception {
        testJetty();
    }
    

    public static class TestHandler extends org.eclipse.jetty.server.handler.AbstractHandler {

        @Override
        public void handle(String arg0, Request arg1, HttpServletRequest arg2,
                HttpServletResponse arg3) throws IOException, ServletException {
            System.out.println(arg1);
        }
    };
}