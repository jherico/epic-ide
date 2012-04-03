package org.epic.debug.cgi.server;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;

import com.google.common.io.CharStreams;

/**
 * A thread which forwards all bytes read from a source Reader
 * to a destination Writer. After the thread terminates,
 * {@link getError} can be called to check if the the Writer
 * was closed properly or an exception has occured.
 */
class StreamForwarder implements Runnable
{
    private final Reader src;
    private final Writer dst;
    private final String name;
    private IOException error;
    
    public StreamForwarder(String name, Reader src, Writer dst)
    {
        this.name = name;
        this.src = src;
        this.dst = dst;
    }
    
    public IOException getError()
    {
        return error;
    }
    
    public void run()
    {
        Thread.currentThread().setName(name);
        try
        {
            CharStreams.copy(src, dst); 
        }
        catch (IOException e)
        {
            error = e;
        }
    }
}