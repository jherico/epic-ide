package org.epic.debug.cgi;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.epic.core.PerlCore;
import org.epic.core.PerlProject;
import org.epic.core.util.PerlExecutableUtilities;
import org.epic.debug.LaunchConfigurationDelegate;
import org.epic.debug.PerlDebugPlugin;
import org.epic.debug.PerlLaunchConfigurationConstants;
import org.epic.debug.cgi.server.EpicCgiHandler;
import org.epic.debug.util.RemotePort;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.output.XMLOutputter;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

/**
 * Executes launch configurations of type "Perl CGI".
 */
public class CGILaunchConfigurationDelegate extends LaunchConfigurationDelegate
{

    private static final List<String> JETTY_COMMANDLINE;

    static
    {
        try
        {
            // build the class path component for the jetty command line
            
            // All the required jars are bundled in the org.epic.lib plugin
            File libDir = new File(FileLocator.getBundleFile(Platform.getBundle("org.epic.lib")), "lib");

            // Start off with the guava jar for convenience
            List<String> classpath = new ArrayList<String>();
            classpath.add(new File(libDir, "guava-11.0.2.jar").getAbsolutePath());
            classpath.add(new File(libDir, "servlet-api-2.5.jar").getAbsolutePath());
            classpath.add(new File(libDir, "log4j-1.2.16.jar").getAbsolutePath());
            classpath.add(new File(libDir, "slf4j-api-1.6.4.jar").getAbsolutePath());
            classpath.add(new File(libDir, "slf4j-log4j12-1.6.4.jar").getAbsolutePath());
            classpath.add(new File(libDir, "jetty-server-7.6.2.v20120308").getAbsolutePath());
            classpath.add(new File(libDir, "jetty-http-7.6.2.v20120308").getAbsolutePath());
            classpath.add(new File(libDir, "jetty-io-7.6.2.v20120308").getAbsolutePath());
            classpath.add(new File(libDir, "jetty-util-7.6.2.v20120308").getAbsolutePath());
            classpath.add(new File(libDir, "jetty-xml-7.6.2.v20120308").getAbsolutePath());
            classpath.add(new File(libDir, "jetty-security-7.6.2.v20120308").getAbsolutePath());
            classpath.add(new File(libDir, "jetty-servlet-7.6.2.v20120308").getAbsolutePath());
            classpath.add(new File(libDir, "jetty-continuation-7.6.2.v20120308").getAbsolutePath());

            // Add ourselves to the classpath, since we define the handler
            // The complexity here is because the classpath differs depending
            // on whether we're deployed as a plugin archive
            {
                URL binUrl = PerlDebugPlugin.getDefault().getBundle().getEntry("/bin");
                if (binUrl != null)
                {
                    binUrl = FileLocator.resolve(binUrl);
                    assert binUrl.getProtocol().equalsIgnoreCase("file");
                    // 'bin' folder exists = we're running inside of
                    // a hosted workbench
                    classpath.add(urlToFile(binUrl));
                }
                else
                {

                    URL dirUrl = FileLocator.resolve(PerlDebugPlugin.getDefault().getBundle().getEntry("/"));
                    if (dirUrl.getProtocol().equalsIgnoreCase("jar"))
                    {
                        // org.epic.debug was deployed as a jar; add this jar
                        // to the classpath
                        String path = dirUrl.getPath();
                        assert path.startsWith("file:");
                        assert path.endsWith(".jar!/");
                        URL jarUrl = new URL(path.substring(0, path.length() - 2));
                        classpath.add(urlToFile(jarUrl));
                    }

                    assert dirUrl.getProtocol().equalsIgnoreCase("file");
                    // org.epic.debug was deployed as a directory:
                    // add this directory to the classpath
                    classpath.add(urlToFile(dirUrl));
                }

            }
            
            List<String> commandLine = new ArrayList<String>();
            commandLine.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
            commandLine.add("-cp");
            commandLine.add(Joiner.on(File.pathSeparator).join(classpath));
            commandLine.add("org.eclipse.jetty.xml.XmlConfiguration");
            commandLine.add("jetty.xml");
            JETTY_COMMANDLINE = Collections.unmodifiableList(commandLine);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static String urlToFile(URL url)
    {
        String urlString = url.toExternalForm();

        if (urlString.matches("^file:/[A-Za-z]:/.*$"))
        {
            // Windows URL with volume letter: file:/C:/foo/bar/blah.txt
            return new File(urlString.substring(6)).getAbsolutePath();
        }
        else
        {
            // Unix URLs look like this: file:/foo/bar/blah.txt
            assert urlString.matches("^file:/[^/].*$");
            return new File(urlString.substring(5)).getAbsolutePath();
        }
    }

    protected void doLaunch(ILaunchConfiguration configuration, String mode, ILaunch launch, IProgressMonitor monitor)
        throws CoreException
    {
        RemotePort debugPort = null;
        if (isDebugMode(launch))
        {
            debugPort = new RemotePort("DebugTarget.mDebugPort");
            debugPort.startConnect();
        }

        try
        {
            IProcess process;
            CGIProxy cgiProxy = new CGIProxy(launch, "CGI Process");
            int serverPort = RemotePort.findFreePort();

            // Make sure we have the lock on the proxy
            synchronized (cgiProxy)
            {
                // Launch the proxy listening thread
                cgiProxy.startListening();
                
                process = startJetty(launch, cgiProxy, serverPort, debugPort);
                try
                {
                    // This will release the lock, allowing the cgiProxy
                    // listening
                    // thread to acquire it (after the connection occurs)
                    cgiProxy.wait(30000);
                }
                catch (InterruptedException e)
                {
                    throw new CoreException(new Status(IStatus.ERROR, PerlDebugPlugin.getUniqueIdentifier(),
                        IStatus.OK, "CGI proxy did not connect", e));
                }
            }

            if (!cgiProxy.isConnected())
            {
                PerlDebugPlugin.getDefault().logError("(CGI-Target) Could not connect to CGI-Proxy");
                launch.terminate();
                return;
            }
            launch.addProcess(cgiProxy);

            openBrowser(launch, serverPort);

            if (debugPort != null)
            {
                if (debugPort.waitForConnect(false) != RemotePort.WAIT_OK)
                {
                    PerlDebugPlugin.errorDialog("Could not connect to debug port!");
                    debugPort.shutdown();
                    launch.terminate();
                    return;
                }
                launch.addDebugTarget(new CGIDebugTarget(launch, process, debugPort, getPathMapper(launch)));
            }
        }
        catch (CoreException e)
        {
            if (debugPort != null) debugPort.shutdown();
            launch.terminate();
            throw e;
        }
    }

    private String getLaunchAttribute(ILaunch launch, String attrName, boolean isPath) throws CoreException
    {
        return getLaunchAttribute(launch, attrName, isPath, null);
    }

    private String getLaunchAttribute(ILaunch launch, String attrName, boolean isPath, String defaultValue)
        throws CoreException
    {
        String attrValue = launch.getLaunchConfiguration().getAttribute(attrName, (String) null);
        if (attrValue == null)
        {
            attrValue = defaultValue;
        }

        if (isPath)
        {
            attrValue = new Path(attrValue).toString();
        }

        return attrValue;
    }

    private String getRelativeURL(ILaunch launch) throws CoreException
    {
        String htmlRootFile = getLaunchAttribute(launch, PerlLaunchConfigurationConstants.ATTR_HTML_ROOT_FILE, true);

        String htmlRootDir = getLaunchAttribute(launch, PerlLaunchConfigurationConstants.ATTR_HTML_ROOT_DIR, true);

        return new Path(htmlRootFile).setDevice(null).removeFirstSegments(new Path(htmlRootDir).segments().length)
            .toString();
    }

    private void openBrowser(ILaunch launch, int httpPort) throws CoreException
    {
        try
        {
            CGIBrowser browser = new CGIBrowser(launch, getRelativeURL(launch), httpPort);
            browser.open();
        }
        catch (CoreException e)
        {
            PerlDebugPlugin.getDefault().logError("Could not start web browser for CGI debugging.", e);
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private String buildJettyConfig(ILaunch launch, CGIProxy cgiProxy, int webServerPort, RemotePort debugPort)
        throws CoreException, IOException
    {
        Element configure = new Element("Configure");
        configure.setAttribute("class", "org.eclipse.jetty.server.Server");
        Document doc = new Document(configure);

        // Connectors
        configure.addContent(new Element("Call").setAttribute("name", "addConnector").addContent(
            new Element("Arg").addContent(new Element("New").setAttribute("class",
                "org.eclipse.jetty.server.nio.SelectChannelConnector").addContent(
                new Element("Set").setAttribute("name", "port").setText(Integer.toString(webServerPort))))));

        // Handlers
        configure.addContent(new Element("Set").setAttribute("name", "handler").addContent(
            new Element("New").setAttribute("class", HandlerList.class.getName()).addContent(
                new Element("Set").setAttribute("name", "handlers").addContent(
                    new Element("Array")
                        .setAttribute("type", Handler.class.getName())
                        .addContent(
                            new Element("Item").addContent(new Element("New").setAttribute("id", "cgiHandler")
                                .setAttribute("class", EpicCgiHandler.class.getName())))
                        .addContent(
                            new Element("Item").addContent(new Element("New").setAttribute("id", "resourceHandler")
                                .setAttribute("class", ResourceHandler.class.getName())))))));

        // CGI Handler config
        {
            Element handler = new Element("Ref").setAttribute("id", "cgiHandler");
            configure.addContent(handler);

            // Ports
            handler.addContent(new Element("Set").setAttribute("name", "portIn").setText(
                Integer.toString(cgiProxy.getInPort())));
            handler.addContent(new Element("Set").setAttribute("name", "portOut").setText(
                Integer.toString(cgiProxy.getOutPort())));
            handler.addContent(new Element("Set").setAttribute("name", "portError").setText(
                Integer.toString(cgiProxy.getErrorPort())));

            // Perl executable
            handler.addContent(new Element("Set").setAttribute("name", "perlExecutable").setText(
                PerlExecutableUtilities.getPerlInterpreterPath()));

            // Perl params
            {
                String perlParams = getLaunchAttribute(launch, PerlLaunchConfigurationConstants.ATTR_PERL_PARAMETERS,
                    false);
                if (perlParams == null) perlParams = "";
                perlParams = perlParams.replaceAll("[\\n\\r]", " ");
                handler.addContent(new Element("Set").setAttribute("name", "perlParams").setText(perlParams));
            }

            // Perl includes
            {
                PerlProject project = PerlCore.create(getProject(launch));

                Element includes;
                handler.addContent(new Element("Set").setAttribute("name", "runIncludes").addContent(
                    includes = new Element("Array").setAttribute("type", String.class.getName())));

                for (String include : (List<String>) PerlExecutableUtilities.getPerlIncArgs(project))
                {
                    includes.addContent(new Element("Item").setText(include));
                }
            }

            // Perl debug includes
            if (isDebugMode(launch))
            {
                handler.addContent(new Element("Set").setAttribute("name", "debugIncludes").addContent(
                    new Element("Array").setAttribute("type", String.class.getName()).addContent(
                        new Element("Item").setText(PerlDebugPlugin.getDefault().getInternalDebugInc().trim()))));
            }

            // CGI root directory
            handler.addContent(new Element("Set").setAttribute("name", "cgiRoot").setText(
                getLaunchAttribute(launch, PerlLaunchConfigurationConstants.ATTR_CGI_ROOT_DIR, true)));

            // CGI URL prefix
            handler.addContent(new Element("Set").setAttribute("name", "prefix").setText("/")); // "/cgi-bin"?

            // CGI suffixes
            {
                Element suffixes;

                handler.addContent(new Element("Set").setAttribute("name", "suffixes").addContent(
                    suffixes = new Element("Array").setAttribute("type", String.class.getName())));

                String suffixesString = getLaunchAttribute(launch,
                    PerlLaunchConfigurationConstants.ATTR_CGI_FILE_EXTENSION, false);
                for (String suffix : Splitter.on(",").trimResults().split(suffixesString))
                {
                    suffixes.addContent(new Element("Item").setText(suffix));
                }
            }

            // Perl runtime environment
            {
                Element envMap;
                handler.addContent(new Element("Set").setAttribute("name", "perlEnv").addContent(
                    envMap = new Element("Map")));

                String[] envs = PerlDebugPlugin.getDebugEnv(launch, debugPort != null ? debugPort.getServerPort() : -1);
                for (String env : envs)
                {
                    int j = env.indexOf('=');
                    if (j <= 0)
                    {
                        continue;
                    }

                    envMap.addContent(new Element("Entry").addContent(new Element("Item").setText(env.substring(0, j)))
                        .addContent(new Element("Item").setText(env.substring(j + 1))));
                }
            }

            // Trigger the connection to the proxy
            handler.addContent(new Element("Call").setAttribute("name", "connectToCGIProxy"));
        }

        // Resource handler configurations
        {
            Element resourceHandler = new Element("Ref").setAttribute("id", "resourceHandler");
            configure.addContent(resourceHandler);

            String htmlRootDir = getLaunchAttribute(launch, PerlLaunchConfigurationConstants.ATTR_HTML_ROOT_DIR, true);
            htmlRootDir = "file:/" + new File(htmlRootDir).getAbsolutePath().replaceAll("\\\\", "/");
            resourceHandler.addContent(new Element("Set").setAttribute("name", "resourceBase").setText(htmlRootDir));
            resourceHandler.addContent(new Element("Set").setAttribute("name", "directoriesListed").setText("true"));
        }

        return new XMLOutputter().outputString(doc);
    }
    
    private IProcess startJetty(ILaunch launch, CGIProxy cgiProxy, int webServerPort, RemotePort debugPort)
        throws CoreException
    {

        try
        {
            String xml = buildJettyConfig(launch, cgiProxy, webServerPort, debugPort);
            File xmlFile = new File(PerlDebugPlugin.getDefault().getStateLocation().toFile(), 
                "jetty.xml");
            Files.write(xml, xmlFile, Charsets.UTF_8);
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new CoreException(new Status(IStatus.ERROR, PerlDebugPlugin.getUniqueIdentifier(), IStatus.OK,
                "Could not create configuration file for web server.", e));
        }

        Process jettyProcess;
        try
        {
            jettyProcess = new ProcessBuilder(JETTY_COMMANDLINE).
                directory(PerlDebugPlugin.getDefault().getStateLocation().toFile()).
                start();
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new CoreException(new Status(IStatus.ERROR, PerlDebugPlugin.getUniqueIdentifier(), IStatus.OK,
                "Could not start embedded web server: Runtime.exec failed", e));
        }

        return DebugPlugin.newProcess(launch, jettyProcess, "Web Server");
    }
}
