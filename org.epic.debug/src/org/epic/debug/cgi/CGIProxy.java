/*
 * Created on 03.04.2004
 *
 * To change the template for this generated file go to
 * Window&gt;Preferences&gt;Java&gt;Code Generation&gt;Code and Comments
 */

package org.epic.debug.cgi;

import java.io.IOException;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IStreamsProxy;
import org.epic.debug.PerlDebugPlugin;
import org.epic.debug.util.OutputStreamMonitor;
import org.epic.debug.util.RemotePort;

/**
 * @author ST
 * 
 *         To change the template for this generated type comment go to
 *         Window&gt;Preferences&gt;Java&gt;Code Generation&gt;Code and Comments
 */
public class CGIProxy extends LaunchWrapperProcess
{
    private volatile boolean mIsConnected;
    private OutputStreamMonitor mMonitorError;
    private OutputStreamMonitor mMonitorOut;
    private OutputStreamMonitor mMonitorIn;
    private String mLabel;
    private RemotePort mInStream;
    private RemotePort mOutStream;
    private RemotePort mErrorStream;
    private boolean mIsTerminated;
    private IStreamsProxy mStreamsProxy;

    public CGIProxy(ILaunch fLaunch, String fLabel)
    {
        super(fLaunch);
//        setAttribute(ATTR_PROCESS_TYPE, "EpicCGIProxy");
        mIsConnected = false;
        mIsTerminated = false;
        mLabel = fLabel;

        mInStream = new RemotePort("CGIProxy.mInStream");
		mInStream.startConnect();
        mOutStream = new RemotePort("CGIProxy.mOutStream");
		mOutStream.startConnect();
        mErrorStream = new RemotePort("CGIProxy.mErrorStream");
		mErrorStream.startConnect();
    }

    private class ProxyThread implements Runnable
    {
        public void run()
        {
            Thread.currentThread().setName("EPIC-Debugger:CGIProxy");
            try
            {
                int ret;
                ret = mInStream.waitForConnect(false);
                if (ret == RemotePort.WAIT_OK)
                    ret = mOutStream.waitForConnect(true);
                if (ret == RemotePort.WAIT_OK)
                    ret = mErrorStream.waitForConnect(true);
                if (ret == RemotePort.WAIT_ERROR)
                    PerlDebugPlugin.getDefault().logError(
                        "Could not connect to CGI-Console");
                if (ret != RemotePort.WAIT_OK)
                {
                    terminate();
                    return;
                }
            } catch (DebugException e)
            {
                PerlDebugPlugin.getDefault().logError(
                    "Could not connect to CGI-Console", 
                    e);
            }

            mMonitorIn = new OutputStreamMonitor(mInStream.getInStream());
            mMonitorOut = new OutputStreamMonitor(mOutStream.getInStream());
            mMonitorError =
            	new OutputStreamMonitor(mErrorStream.getInStream());
            mMonitorIn.startMonitoring();
            mMonitorOut.startMonitoring();
            mMonitorError.startMonitoring();
            mStreamsProxy = new IStreamsProxy() {
                public IStreamMonitor getErrorStreamMonitor()
                {
                    return mMonitorError;
                }

                public IStreamMonitor getOutputStreamMonitor()
                {
                    return mMonitorOut;
                }

                public void write(String input) 
                	throws IOException
                {
                    // we can't provide input to a CGI process
                    // through console
                }
            };
            mIsConnected = true;
            fireCreationEvent();
			// Acquire the CGI proxy lock
            synchronized (CGIProxy.this)
            {
            	// Release the wait started in CGILaunchConfigurationDelegate.doLaunch()
                CGIProxy.this.notify();
            }
        }
    }

    public void startListening()
    {
        new Thread(new ProxyThread()).start();
    }

	public boolean isConnected()
	{
		return mIsConnected;
	}

    public int getErrorPort()
    {
        return mErrorStream.getServerPort();
    }
    
    public int getInPort()
    {
        return mInStream.getServerPort();
    }

    public int getOutPort()
    {
        return mOutStream.getServerPort();
    }
    
	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IProcess#getLabel()
	 */
	public String getLabel()
	{
		return mLabel;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IProcess#getStreamsProxy()
	 */
	public IStreamsProxy getStreamsProxy()
	{
		return mStreamsProxy;
	}

	/* (non-Javadoc)
     * @see org.eclipse.debug.core.model.IProcess#getExitValue()
     */
    public int getExitValue() throws DebugException
    {
        return 0;
    }

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.ITerminate#canTerminate()
	 */
	public boolean canTerminate()
	{
		return !isTerminated();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.ITerminate#isTerminated()
	 */
	public boolean isTerminated()
	{
		return mIsTerminated;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.ITerminate#terminate()
	 */
	public void terminate() throws DebugException
	{
		shutdown();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IStreamsProxy#getErrorStreamMonitor()
	 */
	public IStreamMonitor getErrorStreamMonitor()
	{
		return mMonitorError;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IStreamsProxy#getOutputStreamMonitor()
	 */
	public IStreamMonitor getOutputStreamMonitor()
	{
		return mMonitorOut;
	}

	public IStreamMonitor getInputStreamMonitor()
	{
		return mMonitorIn;
	}

    /**
	 * Fire a debug event marking the creation of this element.
	 */
	private void fireCreationEvent()
	{
		fireEvent(new DebugEvent(this, DebugEvent.CREATE));
	}

	/**
     * Fire a debug event
     */
    private void fireEvent(DebugEvent event)
    {
        DebugPlugin manager = DebugPlugin.getDefault();
        if (manager != null)
        {
            manager.fireDebugEventSet(new DebugEvent[] { event });
        }
    }

	/**
		 * Fire a debug event marking the termination of this process.
		 */
	private void fireTerminateEvent()
	{
		fireEvent(new DebugEvent(this, DebugEvent.TERMINATE));
	}

    void shutdown()
    {
        mMonitorError.kill();
        mMonitorOut.kill();
        mMonitorIn.kill();
        mInStream.shutdown();
        mOutStream.shutdown();
        mErrorStream.shutdown();
        mIsTerminated = true;
		fireTerminateEvent();
    }

}
