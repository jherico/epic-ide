package org.epic.debug.cgi.server;

import java.io.BufferedReader;
import java.io.File;
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
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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
import org.eclipse.jetty.util.log.Logger;
import org.epic.debug.util.ExecutionArguments;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

public class EpicCgiHandler extends org.eclipse.jetty.server.handler.AbstractHandler
{

    private static final Logger LOG = org.eclipse.jetty.util.log.Log.getLogger(EpicCgiHandler.class.getName());

    private Socket diagSocket = null;
    private Socket outSocket = null;
    private Socket errorSocket = null;
    private PrintWriter mDiag;
    private Writer mOut; // forwards CGI stdout to CGI proxy
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

    public void init()
    {
        try
        {
            // Connect to the CGI proxy
            diagSocket = new Socket("localhost", portIn);
            outSocket = new Socket("localhost", portOut);
            errorSocket = new Socket("localhost", portError);

            mError = new PrintWriter(errorSocket.getOutputStream());
            mOut = new OutputStreamWriter(outSocket.getOutputStream());
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

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        // The current implementation of EPIC debugger cannot reliably
        // process concurrent debug connections. Therefore, we serialize
        // processing of CGI requests at the web server level (LOCK).
        synchronized (this)
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

            // //-------------------Environment Variables-------------------
            // //PATH_INFO=/bar/baz
            // //SCRIPT_NAME=/foo.cgi
            // //PATH_TRANSLATED=/rhel5pdi/home/bradd/workspace/_scratch_perl/cgi//bar/baz
            // //QUERY_STRING=x=y
            // //QUERY_STRING=
            // //SERVER_URL=http://localhost:5005
            //
            // response.setContentType("text/html;charset=utf-8");
            // response.setStatus(HttpServletResponse.SC_OK);
            // baseRequest.setHandled(true);
            // response.getWriter().println("<h1>" + cgiFile.getAbsolutePath() +
            // "</h1>");

            diagLine("***********************************************************");
            diagLine("Requested URI: " + request.getRequestURL());

            ProcessBuilder pb = new ProcessBuilder(createCommandLine(cgiFile));
            Map<String, String> env = createEnvironment(request, originalScriptPath, pathInfo);
            env = Maps.filterEntries(env, new Predicate<Map.Entry<String, String>>()
            {
                @Override
                public boolean apply(Entry<String, String> input)
                {
                    return null != input.getValue();
                }

            });
            pb.environment().putAll(env);
            pb.directory(new File(cgiFile.getParentFile().getCanonicalPath()));
            baseRequest.setHandled(true);
            execCGI(pb, baseRequest);
        }
    }

    //
    // public static String getPostData(HttpServletRequest req) {
    // StringBuilder sb = new StringBuilder();
    // try {
    // BufferedReader reader = req.getReader();
    // reader.mark(10000);
    //
    // String line;
    // do {
    // line = reader.readLine();
    // sb.append(line).append("\n");
    // } while (line != null);
    // reader.reset();
    // // do NOT close the reader here, or you won't be able to get the post
    // data twice
    // } catch(IOException e) {
    // logger.warn("getPostData couldn't.. get the post data", e); // This has
    // happened if the request's reader is closed
    // }
    //
    // return sb.toString();
    // }

    @SuppressWarnings("unchecked")
    private String[] createCommandLine(File cgiFile)
    {
        // Get Perl executable and generate comand array
        List<String> commandList = new ArrayList<String>();
        commandList.add(this.perlExecutable);

        // Includes
        commandList.add("-I" + cgiFile.getParentFile().getAbsolutePath().replace('\\', '/'));
        if (null != this.runIncludes)
        {
            for (String include : this.runIncludes)
            {
                commandList.add("-I" + include);
            }
        }

        if (null != this.debugIncludes && this.debugIncludes.length != 0)
        {
            for (String include : this.debugIncludes)
            {
                commandList.add("-I" + include);
            }
            commandList.add("-d"); // Add debug switch
        }

        if (perlParams != null && perlParams.length() > 0)
        {
            ExecutionArguments exArgs = new ExecutionArguments(perlParams);
            commandList.addAll(exArgs.getProgramArgumentsL());
        }

        // The actual CGI to execute
        commandList.add(cgiFile.getAbsolutePath());

        // // Look at the query and check for an =
        // // If no '=', then use '+' as an argument delimiter
        // if (request.query.indexOf("=") == -1)
        // commandList.add(request.query);

        String[] command = commandList.toArray(new String[] {});
        diagLine("---------------------CGI Command Line----------------------");
        for (String part : command)
            diagLine(part);
        return command;
    }

    /**
     * @return the environment passed to the executed CGI script
     */
    private Map<String, String> createEnvironment(HttpServletRequest request, String cgiPath, String pathInfo)
    {
        Map<String, String> env = new TreeMap<String, String>(this.perlEnv);

        @SuppressWarnings("unchecked")
        Enumeration<String> headerNames = request.getHeaderNames();

        while (headerNames.hasMoreElements())
        {
            String headerName = headerNames.nextElement();
            if (HttpHeaders.CONTENT_TYPE.equals(headerName))
            {
                env.put("CONTENT_TYPE", request.getHeader(HttpHeaders.CONTENT_TYPE));
            }
            else if (HttpHeaders.CONTENT_LENGTH.equals(headerName))
            {
                env.put("CONTENT_LENGTH", request.getHeader(HttpHeaders.CONTENT_LENGTH));
            }
            else
            {
                env.put(headerName, request.getHeader(headerName));
            }
        }

        // Ex:
        // prefix = /cgi-bin
        // root = /var/cgi-bin
        // suffixes = [ 'cgi' ]
        // cgi file = foo.cgi (in root)
        // incoming url = http://localhost:8118/cgi-bin/foo/bar/baz

        // SCRIPT_NAME=/foo
        env.put("SCRIPT_NAME", cgiPath);
        // PATH_TRANSLATED=/var/cgi-bin/bar/baz?x=y
        env.put("PATH_TRANSLATED", this.cgiRoot + pathInfo);
        // /bar/baz
        env.put("PATH_INFO", pathInfo);
        // x=y
        env.put("QUERY_STRING", request.getQueryString());

        // Add in the rest of them
        env.put("GATEWAY_INTERFACE", "CGI/1.1");
        env.put("SERVER_SOFTWARE", "Epic CGI Handler");
        env.put("SERVER_NAME", request.getServerName());
        env.put("SERVER_PORT", "" + request.getServerPort());
        String servletPath = request.getServletPath();
        if (null != servletPath)
        {
            env.put("SERVER_URL", servletPath);
        }
        env.put("SERVER_PROTOCOL", request.getProtocol());
        env.put("REQUEST_METHOD", request.getMethod());
        env.put("REMOTE_ADDR", request.getRemoteAddr());
        // env.put("PERLDB_OPTS", System.getenv("PERLDB_OPTS"));
        if ("https".equals(request.getProtocol())) env.put("HTTPS", "on");

        // SESSIONNAME=Console
        // PERL5LIB=c:\imdb\instance\active\data-core\src\IMDbSetup;c:\imdb\instance\active\Code;c:\imdb\instance\active\data-core\Code;c:\imdb\instance\active\odm\Code;export
        // PERLDB_OPTS=
        // PrintRet=0
        diagLine("-------------------Environment Variables-------------------");
        for (Map.Entry<String, String> e : env.entrySet())
        {
            diagLine(e.getKey() + "=" + e.getValue());
        }
        return env;
    }

    private void diagLine(String line)
    {
        LOG.warn(line);
        mDiag.println(line);
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

    private void execCGI(ProcessBuilder pb, Request baseRequest)
    {
        Thread stderrFwd = null;
        Process cgi = null;
        Response response = baseRequest.getResponse();
        try
        {
            pb.redirectErrorStream(true);
            cgi = pb.start();

            if (MimeTypes.FORM_ENCODED.equalsIgnoreCase(baseRequest.getContentType())
                && HttpMethods.POST.equals(baseRequest.getMethod()))
            {
                String postData = getPostData(baseRequest);
                if (null != postData && !postData.isEmpty())
                {
                    OutputStream toCgi = cgi.getOutputStream();
                    toCgi.write(postData.getBytes(Charsets.ISO_8859_1));
                    toCgi.close();
                    mDiag.println("------------------------POST data--------------------------");
                    mDiag.println(postData);
                    mDiag.flush();
                }
            }

            new Thread(new StreamForwarder("EpicCgiHandler.readError", new InputStreamReader(cgi.getErrorStream()),
                mError)).start();

            diagLine("-----------------------Script Output-----------------------");
            BufferedReader in = new BufferedReader(new InputStreamReader(cgi.getInputStream(), Charsets.ISO_8859_1));
            Pattern p = Pattern.compile("(\\S+):\\s*(.*)");
            for (String header = in.readLine(); header != null && header.length() != 0; header = in.readLine())
            {
                mOut.write(header);
                mOut.write("\r\n");
                mOut.flush();

                Matcher m = p.matcher(header);
                if (!m.matches())
                {
                    response.sendError(500, "Missing header from cgi output");
                    return;
                }

                String headerName = m.group(1);
                String headerValue = m.group(2);
                if ("location".equals(headerName.toLowerCase()))
                {
                    response.sendRedirect(headerValue);
                    return;
                }

                response.addHeader(headerName, headerValue);
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
                mError.write("Error 500: " + "CGI failure: " + e.getMessage());
                e.printStackTrace(mError);
            }
            catch (IOException _e)
            { /* not much we can do really */
            }
        }
        finally
        {
            try
            {
                if (stderrFwd != null) stderrFwd.join();
            }
            catch (Exception e)
            {
            }
        }
    }

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
