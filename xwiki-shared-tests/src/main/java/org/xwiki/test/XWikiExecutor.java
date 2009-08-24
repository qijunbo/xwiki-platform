/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.taskdefs.ExecTask;
import org.apache.tools.ant.types.Commandline;

/**
 * Start and stop a xwiki instance.
 * 
 * @version $Id$
 * @since 2.0RC1
 */
public class XWikiExecutor
{
    protected static final Log LOG = LogFactory.getLog(XWikiExecutor.class);

    public static final String DEFAULT_PORT = System.getProperty("xwikiPort", "8080");

    public static final String DEFAULT_STOPPORT = System.getProperty("xwikiStopPort", "8079");

    private static final String DEFAULT_EXECUTION_DIRECTORY = System.getProperty("xwikiExecutionDirectory");

    private static final String START_COMMAND = System.getProperty("xwikiExecutionStartCommand");

    private static final String STOP_COMMAND = System.getProperty("xwikiExecutionStopCommand");

    private static final boolean DEBUG = System.getProperty("debug", "false").equalsIgnoreCase("true");

    private static final String WEBINF_PATH = "/webapps/xwiki/WEB-INF";

    private static final String XWIKICFG_PATH = WEBINF_PATH + "/xwiki.cfg";

    private static final String XWIKIPROPERTIES_PATH = WEBINF_PATH + "/xwiki.properties";

    private static final String LOG4PROPERTIES_PATH = WEBINF_PATH + "/classes/log4j.properties";

    private static final int TIMEOUT_SECONDS = 120;

    private Project project;

    private int port;

    private int stopPort;

    private String executionDirectory;

    public XWikiExecutor(int index)
    {
        this.project = new Project();
        this.project.init();
        this.project.addBuildListener(new AntBuildListener(DEBUG));

        // resolve ports
        String portString = System.getProperty("xwikiPort" + index);
        this.port = portString != null ? Integer.valueOf(portString) : (Integer.valueOf(DEFAULT_PORT) + index);
        String stopPortString = System.getProperty("xwikiStopPort" + index);
        this.stopPort =
            stopPortString != null ? Integer.valueOf(stopPortString) : (Integer.valueOf(DEFAULT_STOPPORT) - index);

        // resolve execution directory
        this.executionDirectory = System.getProperty("xwikiExecutionDirectory" + index);
        if (this.executionDirectory == null) {
            this.executionDirectory = DEFAULT_EXECUTION_DIRECTORY;
            if (index > 0) {
                this.executionDirectory += "-" + index;
            }
        }
    }

    public int getPort()
    {
        return this.port;
    }

    public int getStopPort()
    {
        return this.stopPort;
    }

    public String getExecutionDirectory()
    {
        return this.executionDirectory;
    }

    public void start() throws Exception
    {
        startXWikiInSeparateThread();
        waitForXWikiToLoad();
    }

    private void startXWikiInSeparateThread()
    {
        Thread startThread = new Thread(new Runnable()
        {
            public void run()
            {
                try {
                    startXWiki();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        startThread.start();
    }

    private void startXWiki() throws Exception
    {
        File dir = new File(getExecutionDirectory());
        if (dir.exists()) {
            ExecTask execTask = (ExecTask) this.project.createTask("exec");
            execTask.setDir(new File(getExecutionDirectory()));

            String startCommand = START_COMMAND;
            startCommand = startCommand.replaceFirst(DEFAULT_PORT, String.valueOf(getPort()));
            startCommand = startCommand.replaceFirst(DEFAULT_STOPPORT, String.valueOf(getStopPort()));

            Commandline commandLine = new Commandline(startCommand);
            execTask.setCommand(commandLine);

            execTask.execute();
        } else {
            throw new Exception("Invalid directory from where to start XWiki [" + this.executionDirectory + "]");
        }
    }

    private Task createStopTask() throws Exception
    {
        ExecTask execTask;
        File dir = new File(getExecutionDirectory());
        if (dir.exists()) {
            execTask = (ExecTask) this.project.createTask("exec");
            execTask.setDir(new File(getExecutionDirectory()));

            String stopCommand = STOP_COMMAND;
            stopCommand = stopCommand.replaceFirst(DEFAULT_STOPPORT, String.valueOf(getStopPort()));

            Commandline commandLine = new Commandline(stopCommand);
            execTask.setCommand(commandLine);
        } else {
            throw new Exception("Invalid directory from where to stop XWiki [" + this.executionDirectory + "]");
        }

        return execTask;
    }

    private void waitForXWikiToLoad() throws Exception
    {
        // Wait till the main page becomes available which means the server is started fine
        System.out.println("Checking that XWiki is up and running...");
        URL url = new URL("http://localhost:" + getPort() + "/xwiki/bin/view/Main/WebHome");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        boolean connected = false;
        boolean timedOut = false;
        long startTime = System.currentTimeMillis();
        while (!connected && !timedOut) {
            try {
                connection.connect();
                int responseCode = connection.getResponseCode();
                if (DEBUG) {
                    System.out.println("Result of pinging [" + url + "] = [" + responseCode + "], Message = ["
                        + connection.getResponseMessage() + "]");
                }
                // check the http response code is either not an error, either "unauthorized"
                // (which is the case for products that deny view for guest, for example).
                connected = (responseCode < 400 || responseCode == 401);
            } catch (IOException e) {
                // Do nothing as it simply means the server is not ready yet...
            }
            Thread.sleep(100L);
            timedOut = (System.currentTimeMillis() - startTime > TIMEOUT_SECONDS * 1000L);
        }
        if (timedOut) {
            String message = "Failed to start XWiki in [" + TIMEOUT_SECONDS + "] seconds";
            System.out.println(message);
            stop();
            throw new RuntimeException(message);
        }
    }

    public void stop() throws Exception
    {
        // Stop XWiki
        createStopTask().execute();
    }

    public String getWebInfDirectory()
    {
        return getExecutionDirectory() + WEBINF_PATH;
    }

    public String getXWikiCfgPath()
    {
        return getExecutionDirectory() + XWIKICFG_PATH;
    }

    public String getXWikiPropertiesPath()
    {
        return getExecutionDirectory() + XWIKIPROPERTIES_PATH;
    }

    public String getLog4JPropertiesPath()
    {
        return getExecutionDirectory() + LOG4PROPERTIES_PATH;
    }

    public Properties loadXWikiCfg() throws Exception
    {
        return getProperties(getXWikiCfgPath());
    }

    public Properties loadXWikiProperties() throws Exception
    {
        return getProperties(getXWikiPropertiesPath());
    }

    public Properties loadLog4JProperties() throws Exception
    {
        return getProperties(getLog4JPropertiesPath());
    }

    private Properties getProperties(String path) throws Exception
    {
        Properties properties = new Properties();

        FileInputStream fis;
        try {
            fis = new FileInputStream(path);

            try {
                properties.load(fis);
            } finally {
                fis.close();
            }
        } catch (FileNotFoundException e) {
            LOG.debug("Failed to load properties [" + path + "]", e);
        }

        return properties;
    }

    public void saveXWikiCfg(Properties properties) throws Exception
    {
        saveProperties(getXWikiCfgPath(), properties);
    }

    public void saveXWikiProperties(Properties properties) throws Exception
    {
        saveProperties(getXWikiPropertiesPath(), properties);
    }

    public void saveLog4JProperties(Properties properties) throws Exception
    {
        saveProperties(getLog4JPropertiesPath(), properties);
    }

    private void saveProperties(String path, Properties properties) throws Exception
    {
        FileOutputStream fos = new FileOutputStream(path);
        try {
            properties.store(fos, null);
        } finally {
            fos.close();
        }
    }
}
