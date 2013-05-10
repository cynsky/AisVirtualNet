/* Copyright (c) 2011 Danish Maritime Authority
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this library.  If not, see <http://www.gnu.org/licenses/>.
 */
package dk.dma.ais.virtualnet.server;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.Parameter;
import com.google.inject.Injector;

import dk.dma.commons.app.AbstractDaemon;


/**
 * AisVirtualNetServer server daemon
 */
public class ServerDaemon extends AbstractDaemon {
    
    private static final Logger LOG = LoggerFactory.getLogger(ServerDaemon.class);
    
    @Parameter(names = "-conf", description = "AisVirtualNetServer server configuration file")
    String confFile = "server.xml";
    @Parameter(names = "-users", description = "AisVirtualNetServer server users file")
    String usersFile = "users.txt";
    
    AisVirtualNetServer server;
    
    @Override
    protected void runDaemon(Injector injector) throws Exception {
        LOG.info("Starting AisVirtualNetServer server with configuration: " + confFile);
        
        // Load configuration
        ServerConfiguration conf;
        try {
            conf = ServerConfiguration.load(confFile);
        } catch (FileNotFoundException e) {
            LOG.error(e.getMessage());
            return;
        }
        
        // Start server
        try {
        server = new AisVirtualNetServer(conf, usersFile);
        } catch (IOException e) {
            LOG.error(e.getMessage());
            return;
        }
        server.start();        
    }
    
    @Override
    protected void shutdown() {
        LOG.info("Shutting down");
        if (server != null) {
            server.shutdown();
        }
        super.shutdown();
    }
    
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {            
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                LOG.error("Uncaught exception in thread " + t.getClass().getCanonicalName() + ": " + e.getMessage(), e);
                System.exit(1);
            }
        });
        new ServerDaemon().execute(args);
    }

}
