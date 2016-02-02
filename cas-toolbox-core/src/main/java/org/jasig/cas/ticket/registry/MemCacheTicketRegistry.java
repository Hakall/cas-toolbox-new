/*
 * Licensed to Jasig under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Jasig licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License.  You may obtain a
 * copy of the License at the following location:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jasig.cas.ticket.registry;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import net.spy.memcached.AddrUtil;
import net.spy.memcached.MemcachedClient;
import net.spy.memcached.MemcachedClientIF;
import org.jasig.cas.ticket.ServiceTicket;
import org.jasig.cas.ticket.Ticket;
import org.jasig.cas.ticket.TicketGrantingTicket;
import org.springframework.beans.factory.DisposableBean;
// tokemanager
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Key-value ticket registry implementation that stores tickets in memcached keyed on the ticket ID.
 *
 * @author Scott Battaglia
 * @author Marvin S. Addison
 * @since 3.3
 */
public final class MemCacheTicketRegistry extends AbstractDistributedTicketRegistry implements DisposableBean {

    /** Memcached client. */
    @NotNull
    private final MemcachedClientIF client;

    /** memcached addresses **/
    private final List<InetSocketAddress> addresses = new ArrayList<InetSocketAddress>();;

    /**
     * TGT cache entry timeout in seconds.
     */
    @Min(0)
    private final int tgtTimeout;

    /**
     * ST cache entry timeout in seconds.
     */
    @Min(0)
    private final int stTimeout;


    /**
     * Creates a new instance that stores tickets in the given memcached hosts.
     *
     * @param hostnames                   Array of memcached hosts where each element is of the form host:port.
     * @param ticketGrantingTicketTimeOut TGT timeout in seconds.
     * @param serviceTicketTimeOut        ST timeout in seconds.
     */
    public MemCacheTicketRegistry(final String[] hostnames, final int ticketGrantingTicketTimeOut,
final int serviceTicketTimeOut) {
        try {
            this.client = new MemcachedClient(AddrUtil.getAddresses(Arrays.asList(hostnames)));
        } catch (final IOException e) {
            throw new IllegalArgumentException("Invalid memcached host specification.", e);
        }

        for(String hostname : hostnames){
            String[] hostPort = hostname.split(":");
            addresses.add(new InetSocketAddress(hostPort[0], Integer.parseInt(hostPort[1])));
        }
        this.tgtTimeout = ticketGrantingTicketTimeOut;
        this.stTimeout = serviceTicketTimeOut;
    }

    /**
     * This alternative constructor takes time in milliseconds.
     * It has the timeout parameters in order to create a unique method signature.
     *
     * @param ticketGrantingTicketTimeOut TGT timeout in milliseconds.
     * @param serviceTicketTimeOut ST timeout in milliseconds.
     * @param hostnames  Array of memcached hosts where each element is of the form host:port.
     * @see MemCacheTicketRegistry#MemCacheTicketRegistry(String[], int, int)
     * @deprecated This has been deprecated
     */
    @Deprecated
    public MemCacheTicketRegistry(final long ticketGrantingTicketTimeOut, final long serviceTicketTimeOut,
            final String[] hostnames) {
        this(hostnames, (int) (ticketGrantingTicketTimeOut / 1000), (int) (serviceTicketTimeOut / 1000));
    }

    /**
     * Creates a new instance using the given memcached client instance, which is presumably configured via
     * <code>net.spy.memcached.spring.MemcachedClientFactoryBean</code>.
     *
     * @param client                      Memcached client.
     * @param ticketGrantingTicketTimeOut TGT timeout in seconds.
     * @param serviceTicketTimeOut        ST timeout in seconds.
     */
    public MemCacheTicketRegistry(final MemcachedClientIF client, final int ticketGrantingTicketTimeOut,
            final int serviceTicketTimeOut) {
        this.tgtTimeout = ticketGrantingTicketTimeOut;
        this.stTimeout = serviceTicketTimeOut;
        this.client = client;
    }

    protected void updateTicket(final Ticket ticket) {
        logger.debug("Updating ticket {}", ticket);
        try {
            if (!this.client.replace(ticket.getId(), getTimeout(ticket), ticket).get()) {
                logger.error("Failed updating {}", ticket);
            }
        } catch (final InterruptedException e) {
            logger.warn("Interrupted while waiting for response to async replace operation for ticket {}. "
                        + "Cannot determine whether update was successful.", ticket);
        } catch (final Exception e) {
            logger.error("Failed updating {}", ticket, e);
        }
    }

    public void addTicket(final Ticket ticket) {
        logger.debug("Adding ticket {}", ticket);
        try {
            if (!this.client.add(ticket.getId(), getTimeout(ticket), ticket).get()) {
                logger.error("Failed adding {}", ticket);
            }
        } catch (final InterruptedException e) {
            logger.warn("Interrupted while waiting for response to async add operation for ticket {}."
                    + "Cannot determine whether add was successful.", ticket);
        } catch (final Exception e) {
            logger.error("Failed adding {}", ticket, e);
        }
    }

    public boolean deleteTicket(final String ticketId) {
        logger.debug("Deleting ticket {}", ticketId);
        try {
            return this.client.delete(ticketId).get();
        } catch (final Exception e) {
            logger.error("Failed deleting {}", ticketId, e);
        }
        return false;
    }

    public Ticket getTicket(final String ticketId) {
        try {
            final Ticket t = (Ticket) this.client.get(ticketId);
            if (t != null) {
                return getProxiedTicketInstance(t);
            }
        } catch (final Exception e) {
            logger.error("Failed fetching {} ", ticketId, e);
        }
        return null;
    }

    @Override
    public Collection<Ticket> getTickets() {
        return dumpCachedTGT();
    }

    /**
     * Dump memcached TGT
     *
     */
    public Collection<Ticket> dumpCachedTGT(){
        List<Ticket> resTickets = new ArrayList<Ticket>();

        Map<SocketAddress, Map<String, String>> stats = client.getStats("items");

        for (int i = 0; i < addresses.size(); i++) {
            InetSocketAddress address = addresses.get(i);
            Map<String, String> items = stats.get(address);

            for(String keyItems : items.keySet()) {
                if(keyItems.endsWith(":number")) {
                    String str[] = keyItems.split(":");
                    
                    // get bucket number
                    String bucketNumber = str[1];
                                        
                    // get content of one bucket (items)
                    Map<SocketAddress, Map<String, String>> details = client.getStats("cachedump " + bucketNumber + " 1000000");
                    Map<String, String> tickets = details.get(address);

                    for(String keyTickets : tickets.keySet()) {
                        if(!keyTickets.startsWith("clearPass_")) {
                            // get one ticket
                            Ticket ticket = (Ticket) client.get(keyTickets);
                                    
                            if(ticket == null || !(ticket instanceof TicketGrantingTicket)) {
                                continue;               
                            }
                            
                            TicketGrantingTicket TGT = (TicketGrantingTicket) ticket;
                            resTickets.add(ticket);    
                        }
                    }
                }
            }
        }   
        return resTickets;
    }

    public void destroy() throws Exception {
        this.client.shutdown();
    }

    /**
     * @param sync set to true, if updates to registry are to be synchronized
     * @deprecated As of version 3.5, this operation has no effect since async writes can cause registry consistency issues.
     */
    @Deprecated
    public void setSynchronizeUpdatesToRegistry(final boolean sync) {}

    @Override
    protected boolean needsCallback() {
        return true;
    }

    private int getTimeout(final Ticket t) {
        if (t instanceof TicketGrantingTicket) {
            return this.tgtTimeout;
        } else if (t instanceof ServiceTicket) {
            return this.stTimeout;
        }
        throw new IllegalArgumentException("Invalid ticket type");
    }
}
