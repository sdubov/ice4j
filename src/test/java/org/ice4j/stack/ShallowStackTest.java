/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 package org.ice4j.stack;

import java.net.*;
import java.util.concurrent.*;
import java.util.logging.*;

import static org.junit.jupiter.api.Assertions.*;

import org.ice4j.*;
import org.ice4j.message.*;
import org.ice4j.socket.*;
import org.junit.jupiter.api.*;

/**
 * All unit stack tests should be provided later. I just don't have the time now.
 *
 * @author Emil Ivov
 */
public class ShallowStackTest
{
    /**
     * The <tt>Logger</tt> used by the <tt>ShallowStackTest<tt> class and its
     * instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(ShallowStackTest.class.getName());

    /**
     * The <tt>StunStack</tt> used by this <tt>ShallowStackTest</tt>
     */
    private StunStack    stunStack;
    private MsgFixture   msgFixture = null;

    private TransportAddress dummyServerAddress = null;
    private TransportAddress localAddress = null;

    private final DatagramCollector dgramCollector = new DatagramCollector();

    private IceSocketWrapper   localSock = null;

    private DatagramSocket dummyServerSocket = null;

    /**
     * Initializes whatever sockets we'll be using in our tests.
     *
     * @throws Exception if something goes wrong with socket initialization.
     */
    @BeforeEach
    public void setUp()
        throws Exception
    {
        System.clearProperty(StackProperties.ALWAYS_SIGN);
        System.clearProperty(StackProperties.SOFTWARE);

        msgFixture = new MsgFixture();

        //init the stack
        stunStack = new StunStack();

        //access point
        localSock = new IceUdpSocketWrapper(
            new SafeCloseDatagramSocket(new InetSocketAddress("127.0.0.1", 0)));
        localAddress = new TransportAddress(
            "127.0.0.1", localSock.getLocalPort(), Transport.UDP);
        stunStack.addSocket(localSock);

        //init the dummy server
        dummyServerSocket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
        dummyServerAddress = new TransportAddress(
            "127.0.0.1", dummyServerSocket.getLocalPort(), Transport.UDP);
    }

    /**
     * Releases the sockets we use here.
     *
     * @throws Exception if closing the sockets fails.
     */
    @AfterEach
    public void tearDown()
        throws Exception
    {
        stunStack.removeSocket(localAddress);
        stunStack.shutDown();

        localSock.close();

        dummyServerSocket.close();

        msgFixture = null;
    }


    /**
     * Sends a binding request using the stack to a bare socket, and verifies
     * that it is received and that the contents of the datagram corresponds to
     * the request that was sent.
     *
     * @throws java.lang.Exception if we fail
     */
    @Test
    public void testSendRequest()
        throws Exception
    {
        Request bindingRequest = MessageFactory.createBindingRequest();

        dgramCollector.startListening(dummyServerSocket);

        stunStack.sendRequest(bindingRequest,
                              dummyServerAddress,
                              localAddress,
                              new SimpleResponseCollector());

        //wait for its arrival
        dgramCollector.waitForPacket(1000);

        DatagramPacket receivedPacket = dgramCollector.collectPacket();

        assertTrue((receivedPacket.getLength() > 0),
            "The stack did not properly send a Binding Request");

        Request receivedRequest =
                        (Request)Request.decode(receivedPacket.getData(),
                                            (char)0,
                                            (char)receivedPacket.getLength());
        assertEquals(bindingRequest, receivedRequest,
            "The received request did not match the one that was sent.");

        //wait for retransmissions

        dgramCollector.startListening(dummyServerSocket);

        dgramCollector.waitForPacket(1000);

        receivedPacket = dgramCollector.collectPacket();

        assertTrue((receivedPacket.getLength() > 0),
            "The stack did not retransmit a Binding Request");

        receivedRequest = (Request)Request.decode(
            receivedPacket.getData(),
            0,
            receivedPacket.getLength());
        assertEquals(bindingRequest, receivedRequest,
            "The retransmitted request did not match the original.");
    }

    /**
     * Sends a byte array containing a bindingRequest, through a datagram socket
     * and verifies that the stack receives it alright.
     *
     * @throws java.lang.Exception if we fail
     */
    @Test
    public void testReceiveRequest()
        throws Exception
    {
        SimpleRequestCollector requestCollector = new SimpleRequestCollector();
        stunStack.addRequestListener(requestCollector);

        dummyServerSocket.send(new DatagramPacket(
            msgFixture.bindingRequest2,
            msgFixture.bindingRequest2.length,
            localAddress));

        //wait for the packet to arrive
        requestCollector.waitForRequest();

        Request collectedRequest = requestCollector.collectedRequest;

        assertNotNull(collectedRequest, "No request has been received");

        byte[] expectedReturn = msgFixture.bindingRequest2;
        byte[] actualReturn = collectedRequest.encode(stunStack);
        assertArrayEquals(expectedReturn, actualReturn,
            "Received request was not the same as the one that was sent");
    }

    /**
     * Sends a byte array containing a bindingRequest, through a datagram socket,
     * verifies that the stack receives it properly and then sends a response
     * using the stack. Finally, the response is expected at the other end and
     * compared with the sent one.
     *
     * @throws java.lang.Exception if we fail
     */
    @Test
    public void testSendResponse()
        throws Exception
    {
        //---------- send & receive the request --------------------------------
        SimpleRequestCollector requestCollector = new SimpleRequestCollector();
        stunStack.addRequestListener(requestCollector);

        dummyServerSocket.send(new DatagramPacket(
                                            msgFixture.bindingRequest,
                                            msgFixture.bindingRequest.length,
                                            localAddress));

        //wait for the packet to arrive
        requestCollector.waitForRequest();

        Request collectedRequest = requestCollector.collectedRequest;

        byte[] expectedReturn = msgFixture.bindingRequest;
        byte[] actualReturn = collectedRequest.encode(stunStack);
        assertArrayEquals(expectedReturn, actualReturn,
            "Received request was not the same as the one that was sent");

        //---------- create the response ---------------------------------------
        Response bindingResponse = MessageFactory.create3489BindingResponse(
            new TransportAddress( MsgFixture.ADDRESS_ATTRIBUTE_ADDRESS,
                 MsgFixture.ADDRESS_ATTRIBUTE_PORT, Transport.UDP ),
            new TransportAddress( MsgFixture.ADDRESS_ATTRIBUTE_ADDRESS_2,
                 MsgFixture.ADDRESS_ATTRIBUTE_PORT_2, Transport.UDP),
            new TransportAddress( MsgFixture.ADDRESS_ATTRIBUTE_ADDRESS_3,
                 MsgFixture.ADDRESS_ATTRIBUTE_PORT_3, Transport.UDP));

        //---------- send & receive the response -------------------------------
        dgramCollector.startListening(dummyServerSocket);

        stunStack.sendResponse(collectedRequest.getTransactionID(),
                               bindingResponse,
                               localAddress,
                               dummyServerAddress);

        //wait for its arrival
        dgramCollector.waitForPacket();

        DatagramPacket receivedPacket = dgramCollector.collectPacket();

        assertTrue((receivedPacket.getLength() > 0),
            "The stack did not properly send a Binding Request");

        Response receivedResponse =
            (Response) Response.decode(receivedPacket.getData(),
                                       0,
                                       receivedPacket.getLength());
        assertEquals(bindingResponse, receivedResponse,
            "The received request did not match the one that was sent.");
    }

    /**
     * Performs a basic test on message reception
     *
     * @throws Exception if something fails somewhere.
     */
    @Test
    public void testReceiveResponse()
        throws Exception
    {
        SimpleResponseCollector collector = new SimpleResponseCollector();
        //--------------- send the original request ----------------------------
        Request bindingRequest = MessageFactory.createBindingRequest();

        stunStack.sendRequest(bindingRequest,
                              dummyServerAddress,
                              localAddress,
                              collector);

        //wait for its arrival
        collector.waitForResponse();

        //create the right response
        byte[] response = new byte[msgFixture.bindingResponse.length];
        System.arraycopy(msgFixture.bindingResponse, 0, response, 0,
                         response.length);

        //Set the valid tid.
        System.arraycopy(bindingRequest.getTransactionID(),
                         0,
                         response,
                         8,
                         12);

        //send the response

        dummyServerSocket.send(new DatagramPacket(response,
                                                response.length,
                                                localAddress));

        //wait for the packet to arrive
        collector.waitForResponse();

        Response collectedResponse = collector.collectedResponse;

        byte[] actualReturn = collectedResponse.encode(stunStack);
        assertArrayEquals(response, actualReturn,
            "Received request was not the same as the one that was sent");
    }

    /**
     * Verify StackProperties.FIRST_CTRAN_RETRANS_AFTER can indeed update StunClientTransaction.Retransmitter
     */
    @Test
    public void testRetransmissionOriginalWait()
        throws Exception
    {
        long originalWait = 200; // milliseconds
        System.setProperty(StackProperties.FIRST_CTRAN_RETRANS_AFTER, String.valueOf(originalWait));

        Request bindingRequest = MessageFactory.createBindingRequest();

        dgramCollector.startListening(dummyServerSocket);

        long firstTime = System.nanoTime();

        stunStack.sendRequest(bindingRequest,
                dummyServerAddress,
                localAddress,
                new SimpleResponseCollector());

        //wait for its arrival
        dgramCollector.waitForPacket(1000);
        DatagramPacket receivedPacket = dgramCollector.collectPacket();

        assertTrue((receivedPacket.getLength() > 0),
            "The stack did not properly send a Binding Request");

        Request receivedRequest =
                (Request)Request.decode(receivedPacket.getData(),
                        (char)0,
                        (char)receivedPacket.getLength());
        assertEquals(bindingRequest, receivedRequest,
            "The received request did not match the one that was sent.");

        // wait for the 1st retransmission with originalWait
        dgramCollector.startListening(dummyServerSocket);
        dgramCollector.waitForPacket(1000);
        receivedPacket = dgramCollector.collectPacket();

        assertTrue((receivedPacket.getLength() > 0),
            "The stack did not retransmit a Binding Request");

        receivedRequest = (Request) Request.decode(
            receivedPacket.getData(),
            0,
            receivedPacket.getLength());
        assertEquals(bindingRequest, receivedRequest,
            "The retransmitted request did not match the original.");

        // verify the retransmission is longer than the originalWait
        assertTrue(System.nanoTime() - firstTime >= TimeUnit.MILLISECONDS.toNanos(originalWait));

        System.clearProperty(StackProperties.FIRST_CTRAN_RETRANS_AFTER);
    }

    //--------------------------------------- listener implementations ---------
    /**
     * A simple utility that allows us to asynchronously collect messages.
     */
    public static class SimpleResponseCollector
        extends AbstractResponseCollector
    {

        /**
         * The response that we've just collected or <tt>null</tt> if none
         * arrived while we were waiting.
         */
        Response collectedResponse = null;

        /**
         * Notifies this <tt>ResponseCollector</tt> that a transaction described by
         * the specified <tt>BaseStunMessageEvent</tt> has failed. The possible
         * reasons for the failure include timeouts, unreachable destination, etc.
         *
         * @param event the <tt>BaseStunMessageEvent</tt> which describes the failed
         * transaction and the runtime type of which specifies the failure reason
         * @see AbstractResponseCollector#processFailure(BaseStunMessageEvent)
         */
        protected synchronized void processFailure(BaseStunMessageEvent event)
        {
            String msg;

            if (event instanceof StunFailureEvent)
                msg = "Unreachable";
            else if (event instanceof StunTimeoutEvent)
                msg = "Timeout";
            else
                msg = "Failure";
            logger.info(msg);
            notifyAll();
        }

        /**
         * Logs the received response and notifies the wait method.
         *
         * @param response a <tt>StunMessageEvent</tt> which describes the
         * received STUN <tt>Response</tt>
         */
        public synchronized void processResponse(StunResponseEvent response)
        {
            collectedResponse = (Response) response.getMessage();
            logger.finest("Received response.");
            notifyAll();
        }

        /**
         * Blocks until a request arrives or 50 ms pass.
         */
        public synchronized void waitForResponse()
        {
            try
            {
                if (collectedResponse == null)
                    wait(50);
            }
            catch (InterruptedException e)
            {
                logger.log(Level.INFO, "oops", e);
            }
        }
    }

    /**
     * A utility class for asynchronously collecting requests.
     */
    public class SimpleRequestCollector
        implements RequestListener
    {
        /**
         * The one request that this collector has received or <tt>null</tt> if
         * none arrived while we were waiting.
         */
        private Request collectedRequest = null;

        /**
         * Indicates that a <tt>StunRequest</tt> has just been received.
         *
         * @param evt the <tt>StunMessageEvent</tt> containing the details of
         * the newly received request.
         */
        public void processRequest(StunMessageEvent evt)
        {
            synchronized(this)
            {
                collectedRequest = (Request)evt.getMessage();
                stunStack.removeRequestListener(this);
                logger.finest("Received request.");
                notifyAll();
            }
        }

        /**
         * Blocks until a request arrives or 50 ms pass.
         */
        public void waitForRequest()
        {
            synchronized(this)
            {
                if (collectedRequest != null)
                    return;

                try
                {
                    wait(50);
                }
                catch (InterruptedException e)
                {
                    logger.log(Level.INFO, "oops", e);
                }
            }
        }
    }
/*
    public static Test suite()
    {
        TestSuite suite = new TestSuite();
        suite.addTest(new ShallowStackTest(
            "testSendResponse"));
        suite.addTest(new ShallowStackTest(
            "testSendResponse"));
        suite.addTest(new ShallowStackTest(
            "testSendResponse"));
        suite.addTest(new ShallowStackTest(
            "testSendResponse"));
        return suite;
    }
*/
}
