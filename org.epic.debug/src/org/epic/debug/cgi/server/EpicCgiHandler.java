package org.epic.debug.cgi.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FilterWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.http.HttpMethods;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.epic.debug.util.ExecutionArguments;

import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * Handler for implementing cgi/1.1 interface. This implementation allows either
 * suffix matching (e.g. .cgi) to identify cgi scripts, or prefix matching (e.g.
 * /cgi-bin). Defaults to "/". <br>
 * 
 * @author Brad Davis
 * @version 2.0, 2012/04/03
 */
public class EpicCgiHandler extends org.eclipse.jetty.server.handler.AbstractHandler
{
    private static final org.eclipse.jetty.util.log.Logger LOG = org.eclipse.jetty.util.log.Log.getLogger(EpicCgiHandler.class.getName());
//    private static final Logger LOG = LoggerFactory.getLogger(EpicCgiHandler.class);

    private Socket diagSocket;
	private Socket outSocket;
	private Socket errorSocket;
    
    private PrintWriter mDiag; // diagnostic info to CGI proxy
    private PrintWriter mOut; // forwards CGI stdout to CGI proxy
    private PrintWriter mError; // forwards CGI stderr to CGI proxy

    private int portIn;
    private int portOut;
    private int portError;

    private String perlExecutable;
    private String perlParams;
    private String[] runIncludes = {};
    private String[] debugIncludes = {};
    private String cgiRoot;
    private String prefix;
    private Map<String, String> perlEnv = new HashMap<String, String>();
    private Set<String> suffixes = new HashSet<String>();

    private static final Function<String, String> INCLUDE_PREPENDER = 
        new Function<String, String>() {
            @Override
            public String apply(String arg0) {
                // TODO Auto-generated method stub
                return "-I" + arg0;
            }
        };

    public static final class SideWriter<T extends Writer> extends FilterWriter {
        private final T side; 
        
        public SideWriter(Writer out, T side)
        {
            super(out);
            this.side = side;
        }

        public T getSide() {
            return side;
        }

        public Writer append(CharSequence csq) throws IOException {
            if (csq == null)
                write("null");
            else
                write(csq.toString());
            return this;
        }
    
        @Override
        public void write(char[] cbuf, int off, int len) throws IOException
        {
            side.write(cbuf, off, len);
            super.write(cbuf, off, len);
            super.flush();
        }
    }
      
    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        // The current implementation of EPIC debugger cannot reliably
        // process concurrent debug connections. Therefore, we serialize
        // processing of CGI requests at the web server level (LOCK).
        synchronized (EpicCgiHandler.class)
        {
            String url = baseRequest.getRequestURI();
            if (prefix != null && !url.startsWith(prefix)) return;
            String cgiUrl = url.substring(prefix.length());
            LOG.info(request.getRequestURL().toString());

            LinkedList<String> urlParts = new LinkedList<String>();
            Iterables.addAll(urlParts, Splitter.on("/").split(cgiUrl));
            File cgiFile = findCgiFile(urlParts);
            if (null == cgiFile)
            {
                return;
            }

            LOG.info(cgiFile.getAbsolutePath());
            String pathInfo = urlParts.isEmpty() ? "" : "/" + Joiner.on('/').join(urlParts);
            LOG.info("path info: " + pathInfo);
            String originalScriptPath = url.substring(0, url.length() - pathInfo.length());
            LOG.info("original:" + originalScriptPath);


            mDiag.println("***********************************************************");
            mDiag.println("Requested URI: " + request.getRequestURL());

            ProcessBuilder pb = new ProcessBuilder(createCommandLine(cgiFile));
            pb.environment().putAll(createEnvironment(request, originalScriptPath, pathInfo));
            pb.directory(new File(cgiFile.getParentFile().getCanonicalPath()));
            baseRequest.setHandled(true);
            execCGI(pb, baseRequest);
        }
    }

    /**
     * Opens communication channels to the EPIC CGI proxy running
     * inside of the Eclipse JVM. The script output and diagnostic
     * information are forwarded to this proxy.
     */
    public void connectToCGIProxy()
    {
        try
        {
            diagSocket = new Socket("localhost", portIn);
            outSocket = new Socket("localhost", portOut);
            errorSocket = new Socket("localhost", portError);

            mError = new PrintWriter(errorSocket.getOutputStream());
            mOut = new PrintWriter(outSocket.getOutputStream());
            mDiag = new PrintWriter(diagSocket.getOutputStream(), true);
        }
        catch (UnknownHostException e)
        {
            throw new RuntimeException(e);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * @return the command line used to execute the CGI script
     */
    @SuppressWarnings("unchecked")
    private String[] createCommandLine(File cgiFile)
    {
        // Get Perl executable and generate comand array
        List<String> commandList = new ArrayList<String>();
        
        commandList.add(this.perlExecutable);

        // Add absolute path to local working directory to make
        // perl -d refer to modules in the same directory by their
        // absolute rather than relative paths (relevant when setting
        // breakpoints).
        //
        // TODO: Cygwin path translation is missing here, this might
        // cause problems; compare to LocalLaunchConfigurationDelegate
        commandList.add("-I" +
            cgiFile.getParentFile().getAbsolutePath().replace('\\', '/'));

        commandList.addAll(Lists.transform(Arrays.asList(this.runIncludes), 
        	INCLUDE_PREPENDER));

        if (this.debugIncludes.length != 0)
        {
	        commandList.addAll(Lists.transform(Arrays.asList(this.debugIncludes), 
    	    	INCLUDE_PREPENDER));
            commandList.add("-d"); // Add debug switch
        }

        if (perlParams != null && perlParams.length() > 0)
        {
            ExecutionArguments exArgs = new ExecutionArguments(perlParams);
            commandList.addAll(exArgs.getProgramArgumentsL());
        }

        try { commandList.add(cgiFile.getCanonicalPath()); }
        catch (IOException e) { commandList.add(cgiFile.getAbsolutePath()); }

        // // Look at the query and check for an =
        // // If no '=', then use '+' as an argument delimiter
        // if (request.query.indexOf("=") == -1)
        // commandList.add(request.query);

        mDiag.println("---------------------CGI Command Line----------------------");
        for (String part : commandList)
            mDiag.println(part);

        return commandList.toArray(new String[] {});
    }

    /**
     * @return the environment passed to the executed CGI script
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> createEnvironment(HttpServletRequest request, String cgiPath, String pathInfo)
    {
        Map<String, String> env = new TreeMap<String, String>();

        Enumeration<String> keys = request.getHeaderNames();
        while (keys.hasMoreElements())
        {
            String key = keys.nextElement();
            if (HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(key))
            {
                env.put("CONTENT_TYPE", request.getHeader(HttpHeaders.CONTENT_TYPE));
            }
            else if (HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(key))
            {
                env.put("CONTENT_LENGTH", request.getHeader(HttpHeaders.CONTENT_LENGTH));
            }
            else
            {
                env.put("HTTP_" + key.toUpperCase().replace('-', '_'),
                	request.getHeader(key));
            }
        }

        // Add in the rest of them
        
        env.put("GATEWAY_INTERFACE", "CGI/1.1");
        env.put("SERVER_SOFTWARE", "Epic CGI Handler");
        env.put("SERVER_NAME", request.getServerName());
        env.put("PATH_INFO", pathInfo);
        
        env.put("SCRIPT_NAME", cgiPath);

        env.put("SERVER_PORT", "" + request.getServerPort());
        env.put("REMOTE_ADDR", request.getRemoteAddr());
        env.put("PATH_TRANSLATED", this.cgiRoot + pathInfo);
        env.put("REQUEST_METHOD", request.getMethod());
        env.put("SERVER_PROTOCOL", request.getProtocol());
        env.put("QUERY_STRING", request.getQueryString());

        // env.put("PERLDB_OPTS", System.getenv("PERLDB_OPTS"));
        if ("https".equals(request.getProtocol())) env.put("HTTPS", "on");

        String servletPath = request.getServletPath();
        if (null != servletPath)
        {
            env.put("SERVER_URL", servletPath);
        }
        
        // TODO Append the "custom" environment variables (if requested)
        
        // Append environment variables provided by EPIC
        // (configurable with the CGI Environment tab; if nothing
        // is configured, the environment of the workbench is used)

        env.putAll(perlEnv);
        
        return Maps.filterValues(env, new Predicate<String>() {
            @Override
            public boolean apply(String input)
            {
                return input != null; 
            }
        });
    }

    /**
     * Executes the given CGI script file using the provided
     * command line and environment. Script stdout is returned
     * both to the browser and to the CGI proxy. Script stderr
     * is returned only to the CGI proxy.
     */
    private void execCGI(
    	ProcessBuilder pb, 
    	Request baseRequest)
    {
        mDiag.println("***********************************************************");
//        mDiag.println("Requested URI: " +
//            request.props.getProperty("url.orig", request.url));
        mDiag.println("---------------------CGI Command Line----------------------");
        for (String s : pb.command()) mDiag.println(s); 
        mDiag.println("-------------------Environment Variables-------------------");
        for (Map.Entry<String, String> e : pb.environment().entrySet()) mDiag.println(e.getKey() + "=" + e.getValue());
            
        Process cgi = null;
        Thread stderrFwd = null;
        Response response = baseRequest.getResponse();
        try
        {
            cgi = pb.start();
			String postData;
            if (MimeTypes.FORM_ENCODED.equalsIgnoreCase(baseRequest.getContentType())
                && HttpMethods.POST.equals(baseRequest.getMethod())
                && (null != (postData = getPostData(baseRequest))) 
                && !postData.isEmpty())
            {
                OutputStream toCgi = cgi.getOutputStream();
   	            toCgi.write(postData.getBytes());
                toCgi.close();
                mDiag.println("------------------------POST data--------------------------");
                mDiag.println(postData);
                mDiag.flush();
            }

            stderrFwd = new Thread(new StreamForwarder("EpicCgiHandler.readError", 
            	new InputStreamReader(cgi.getErrorStream()),
                mError
            ));

            stderrFwd.start();

            mDiag.println("-----------------------Script Output-----------------------");
            
            // Now get the output of the cgi script. Start by reading the
            // "mini header", then just copy the rest

            BufferedReader in = new BufferedReader(new InputStreamReader(cgi.getInputStream(), Charsets.ISO_8859_1));
            Pattern p = Pattern.compile("(\\S+):\\s*(.*)");
            for (String head = in.readLine(); head != null && head.length() != 0; head = in.readLine())
            {
                mOut.write(head);
                mOut.write("\r\n");
                mOut.flush();

                Matcher m = p.matcher(head);
                if (!m.matches())
                {
                    response.sendError(500, "Missing header from cgi output");
                    mError.write(
                        "Error 500: Missing header from cgi output"
                        );
                    return;
                }

                String headerName = m.group(1);
                String headerValue = m.group(2);
                if ("status".equalsIgnoreCase(headerName)) {
                    try
                    {
                        
	                	int status = Integer.parseInt(headerValue.trim());
	                	response.setStatus(status);
                    }
                    catch (NumberFormatException e) { }
                }
                else if ("content-type".equalsIgnoreCase(headerName))
                {
                    response.setContentType(headerValue);
                }
                else if ("location".equals(headerName.toLowerCase()))
                {
                    response.setStatus(302);
                    response.addHeader(headerName, headerValue);
                }
                else
                {
                    response.addHeader(headerName, headerValue);
                }
            }
            mOut.write("\r\n");
            mOut.flush();
            /*
             * Now copy the rest of the data into a buffer, so we can count it.
             * we should be doing chunked encoding for 1.1 capable clients XXX
             */
            Writer buff = new OutputStreamWriter(response.getOutputStream(), Charsets.UTF_8);
            char[] buf = new char[1024];
            int bread;
            while ((bread = in.read(buf, 0, buf.length)) > 0)
            {
                buff.write(buf, 0, bread);
                mOut.write(buf, 0, bread);
                mOut.flush();
            }
            buff.flush();
//            SideWriter<StringWriter> out = 
//                new SideWriter<StringWriter>(mOut, new StringWriter());
//            
//            CharStreams.copy(in, out);
//            out.flush();
//            
//            response.getWriter().write(out.getSide().toString());
            response.complete();
            LOG.info("CGI output " + response.getContentCount() + " bytes.");
            cgi.waitFor();            
        }
        catch (Exception e)
        {
            if (cgi != null) cgi.destroy();
            
            StringWriter trace = new StringWriter();
            e.printStackTrace(new PrintWriter(trace, true));
            try
            {
                response.sendError(500, "CGI failure" + trace.toString());
                mError.write(
                	"Error 500: " + "CGI failure: " + e.getMessage()
                    );
                e.printStackTrace(mError);
            }
            catch (IOException _e) { /* not much we can do really */}
        }
        finally
        {
            try
            {
                if (stderrFwd != null) stderrFwd.join();
            }
            catch (Exception e) { }
        }
    }
    
    /**
     * Resolves the CGI script file to be executed based on the
     * requested URI, configured CGI suffixes and the root directory.  
     * 
     * @return null if the resolution algorithm fails;
     *         otherwise a 2-element array with:
     *         File cgiFile (the resolved CGI script file) and
     *         Integer pathInfoStartIndex (index in the uri at
     *         which the CGI path ends and PATH_INFO to be passed
     *         into the script begins)
     */
    private File findCgiFile(Deque<String> source)
    {
        List<String> dest = new ArrayList<String>();
        Joiner joiner = Joiner.on('/');
        String part;
        while (null != (part = source.poll()))
        {
            // get the extension portion of s if any
            String ext = null;
            int extBegin;
            if (-1 != (extBegin = part.lastIndexOf('.')))
            {
                ext = part.substring(extBegin + 1);
            }
            dest.add(part);

            String candidate = joiner.join(dest);
            // Already has a candidate extension
            if (ext != null && suffixes.contains(ext))
            {
                File retVal = new File(cgiRoot, candidate);
                if (retVal.isFile())
                {
                    return retVal;
                }
            }
            else
            {
                // Try all the suffixes in turn
                for (String suffix : suffixes)
                {
                    File retVal = new File(cgiRoot, candidate + "." + suffix);
                    if (retVal.isFile())
                    {
                        return retVal;
                    }
                }
            }
        }
        return null;
    }

    public static String getPostData(HttpServletRequest req)
    {
        StringBuilder sb = new StringBuilder();
        try
        {
            BufferedReader reader = req.getReader();
            reader.mark(10000);
            String line;
            do
            {
                line = reader.readLine();
                sb.append(line).append("\n");
            }
            while (line != null);
            reader.reset();
            // do NOT close the reader here, or you won't be able to get the
            // post data twice
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }

        return sb.toString();
    }


    public int getPortIn()
    {
        return portIn;
    }

    public void setPortIn(int portIn)
    {
        this.portIn = portIn;
    }

    public int getPortOut()
    {
        return portOut;
    }

    public void setPortOut(int portOut)
    {
        this.portOut = portOut;
    }

    public int getPortError()
    {
        return portError;
    }

    public void setPortError(int portErr)
    {
        this.portError = portErr;
    }

    public String getPerlExecutable()
    {
        return perlExecutable;
    }

    public void setPerlExecutable(String perlExecutable)
    {
        this.perlExecutable = perlExecutable;
    }

    public String getPerlParams()
    {
        return perlParams;
    }

    public void setPerlParams(String perlParams)
    {
        this.perlParams = perlParams;
    }

    public String[] getRunIncludes()
    {
        return runIncludes;
    }

    public void setRunIncludes(String... runIncludes)
    {
        this.runIncludes = runIncludes;
    }

    public String[] getDebugIncludes()
    {
        return debugIncludes;
    }

    public void setDebugIncludes(String... debugIncludes)
    {
        this.debugIncludes = debugIncludes;
    }

    public String getPrefix()
    {
        return prefix;
    }

    public void setPrefix(String prefix)
    {
        if (null != prefix) {
            if (!prefix.startsWith("/")) {
                prefix = "/" + prefix; 
            }
            if (!prefix.endsWith("/")) {
                prefix = prefix + "/"; 
            }
        }
        this.prefix = prefix;
    }

    public Set<String> getSuffixes()
    {
        return suffixes;
    }

    public void setSuffixes(String... suffixes)
    {
        this.suffixes = new HashSet<String>();
        for (String suffix : suffixes)
        {
            if (suffix.startsWith("."))
            {
                suffix = suffix.substring(1);
            }
            this.suffixes.add(suffix);

        }
    }

    public String getCgiRoot()
    {
        return cgiRoot;
    }

    public void setCgiRoot(String cgiRoot)
    {
        this.cgiRoot = cgiRoot;
    }

    public Map<String, String> getPerlEnv()
    {
        return perlEnv;
    }

    public void setPerlEnv(Map<String, String> perlEnv)
    {
        this.perlEnv = perlEnv;
    }

}
