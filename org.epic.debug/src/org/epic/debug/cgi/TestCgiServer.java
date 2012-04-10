package org.epic.debug.cgi;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.epic.debug.util.RemotePort;

public class TestCgiServer {
    
    public static final String FILE = 
        "C:/Users/bdavis/runtime-EclipseApplication/.metadata/.plugins/org.epic.debug/jetty.xml";
//    public static final String FILE = 
//        "c:/var/cgi/jetty.xml";
    public static void testJetty() throws Exception {
        RemotePort placeholder = new RemotePort("placeholder");
        placeholder.startConnect();

        CGIProxy proxy = new CGIProxy(null, "foo");
        
        synchronized (proxy) {
            proxy.startListening();
            org.eclipse.jetty.xml.XmlConfiguration.main(new String[] {FILE});
            proxy.wait();
        }
        System.out.println("main finished");
    }
    
    public static void main(String[] args) throws Exception {
        testJetty();
    }
    

    public static class TestHandler extends org.eclipse.jetty.server.handler.AbstractHandler {

        public void handle(String arg0, Request arg1, HttpServletRequest arg2,
                HttpServletResponse arg3) throws IOException, ServletException {
            System.out.println(arg1);
        }
    };
}