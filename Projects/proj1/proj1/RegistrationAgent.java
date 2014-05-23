import java.io.IOException;
import java.net.*;
import java.nio.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

public class RegistrationAgent {
	private static final int TIMEOUT = 5000;
	private static final int MAX_FETCH_BUFFER_LEN = 5000;
	private static final int MAX_NUM_TRIES = 3;

	// two bytes (0xC461) for header
	private static final byte FST_HEADER_BYTE = (byte) 0x61;
	private static final byte SND_HEADER_BYTE = (byte) 0xC4;

	private static final String ASK_INPUT = 
			"Type in r(egister), f(etch), u(nregister), p(robe), or q(uit): ";

	// special command constants
	private static final String REGISTER = "r";
	private static final String FETCH = "f";
	private static final String UNREGISTER = "u";
	private static final String PROBE = "p";
	private static final String QUIT = "q";

	// message type constants
	private static final byte R_MSG_TYPE = (byte) 0x1;
	private static final byte RR_MSG_TYPE = (byte) 0x2;
	private static final byte F_MSG_TYPE = (byte) 0x3;
	private static final byte FR_MSG_TYPE = (byte) 0x4;
	private static final byte U_MSG_TYPE = (byte) 0x5;
	private static final byte P_MSG_TYPE = (byte) 0x6;
	private static final byte ACK_MSG_TYPE = (byte) 0x7;

	// socket with port number p in spec
	private static DatagramSocket fstSocket = null;
	// socket with port number p + 1 in spec
	private static DatagramSocket sndSocket = null;

	private static byte seqNum = 0;  // sequence number for the messages
	
	private static Map<Integer, Thread> reregHandlers;
	private static Map<Integer, Boolean> portLocks;

	private static InetAddress hostName;
	private static int servicePort;
	
	public static void main (String[] args) {
		if (args.length != 2) {
			System.out.println("Client Usage: java RegistrationAgent " + 
		                       "<registration service host name> <service port>");
			System.exit(1);
		}
		
		reregHandlers = new HashMap<Integer, Thread>();
		portLocks = new HashMap<Integer, Boolean>();

		try {
			fstSocket = new DatagramSocket();
			fstSocket.setSoTimeout(TIMEOUT);

			sndSocket = new DatagramSocket(fstSocket.getLocalPort() + 1);

			// retrieve the host name and port number the user passed in
			hostName = InetAddress.getByName(args[0]);
			servicePort = Integer.valueOf(args[1]).intValue();

			// ask for user input
			Scanner sc = new Scanner(System.in);
			System.out.println(ASK_INPUT);

			while (sc.hasNextLine()) {
				// get the user input with leading and 
				// trailing whitespace omitted
				String input = (sc.nextLine()).trim();
				
				// if user typed in nothing or spaces, prompt for input again
				if (input.isEmpty()) {
					System.out.println(ASK_INPUT);
					continue;
				} else if (input.equals(QUIT)) {
					// if user typed in "q", close the sockets 
					// and let another thread (if any) run
					closeSockets();
					
					for (Integer key : reregHandlers.keySet()) {
						Thread nextToRun = reregHandlers.get(key);
						nextToRun.interrupt();
					}
					
					// get out of the while loop after
					// we return back to execute this thread
					break;
				} else {
					inputHandlerHelper(input);
				}
				
				System.out.println(ASK_INPUT);
			}
			
			// close the scanner after we done with reading inputs
			sc.close();
		} catch (SocketException e) {
			System.out.println("Socket: " + e.getMessage());			
		} catch (UnknownHostException e) {
			System.out.println("UnknownHost: " + e.getMessage());
		} catch (IOException e) {
			System.out.println("IO: " + e.getMessage());
		} finally {
			closeSockets();
		}
	}
	
	/**
	 * A private helper method to close the global sockets of this
	 * registration agent.
	 */
	private static void closeSockets() {
		if (fstSocket != null) {
			fstSocket.close();
			fstSocket = null;
		}
		
		if (sndSocket != null) {
			sndSocket.close();
			sndSocket = null;
		}
	}

	/**
	 * Helper method to handle the user input when user typed in
	 * something other than empty line, spaces, or "q" (quit).
	 * @throws IOException 
	 */
	private static void inputHandlerHelper(String input) throws IOException {
		if (input == null)
			throw new IllegalArgumentException("Arguemnt input cannot be null.");

		// Split the input using space as delimiter, and check the command 
		// the user typed in. If it is not one of "r", "f", "u", or "p", 
		// print the message to tell the user.
		String[] cmdAndArgs = input.split(" ");
		if (!(cmdAndArgs[0].length() == 1 || cmdAndArgs[0].equals(REGISTER) || 
		      cmdAndArgs[0].equals(FETCH) || cmdAndArgs[0].equals(PROBE) || 
		      cmdAndArgs[0].equals(UNREGISTER))) {
			System.out.println("Unsupported command: " + cmdAndArgs[0]);
			return;
		}

		DatagramPacket response;
		byte[] msg;
		
		if (cmdAndArgs[0].equals(REGISTER)) {
			// check if user passed in exactly 3 arguments with "r" command
			if (cmdAndArgs.length != 4) {
				System.out.println("Register Usage: " + 
							       "r <port num> <data> <service name>");
				return;
			}

			int port = Integer.valueOf(cmdAndArgs[1]).intValue();
			int data = (int) Long.valueOf(cmdAndArgs[1]).longValue();
			String name = cmdAndArgs[3];
			int nameLen = name.length();

			ByteBuffer rbb = buildMsgHeader(R_MSG_TYPE, nameLen + 15, seqNum);
			msg = buildRegisterMsg(rbb, InetAddress.getLocalHost().getAddress(), 
								   port, data, name, nameLen);
			seqNum++;

			DatagramPacket rReq = new DatagramPacket(msg, msg.length, 
													 hostName, servicePort);
			fstSocket.send(rReq);
			response = requestForResponse(rReq, 6, "register");

			int lifeTime;
			if (response == null) {
				System.out.println("Register failed.");
				return;
			} else {
				lifeTime = getLifeTime(response.getData());
				String ip = InetAddress.getLocalHost().getHostAddress();
				
				printRegisterSucceedMsg(ip, port, lifeTime, false);
			}
			
			int reregInterval = 0;
			if (lifeTime >= 30)
				reregInterval = lifeTime - 30;

			ReregHandler rrHandler = new ReregHandler(rReq, reregInterval, port);
			Thread reregThread = new Thread(rrHandler);

			// lock the port to indicate it's in used and 
			// let the reregister thread starts
			acquireLock(port);
			reregHandlers.put(port, reregThread);
			reregThread.start();

			ProbeHandler pbHandler = new ProbeHandler();
			Thread pbHandleThread = new Thread(pbHandler);
			pbHandleThread.start();
		} else if (cmdAndArgs[0].equals(FETCH)) {
			// check if user passed in exactly 0 or 1 argument with "f" command
			if (cmdAndArgs.length != 1 && cmdAndArgs.length != 2) {
				System.out.println("Fetch Usage: f <name prefix>");
				return;
			}

			if (cmdAndArgs.length == 1) {
				ByteBuffer fbb = buildMsgHeader(F_MSG_TYPE, 5, seqNum);
				msg = buildFetchMsg(fbb, null, 0);
			} else {
				int argLen = cmdAndArgs[1].length();
				ByteBuffer fbb = buildMsgHeader(F_MSG_TYPE, argLen + 5, seqNum);
				msg = buildFetchMsg(fbb, cmdAndArgs[1], argLen);
			}
			seqNum++;
			
			DatagramPacket fReq = new DatagramPacket(msg, msg.length, 
					 								 hostName, servicePort);
			
			fstSocket.send(fReq);
			response = requestForResponse(fReq, MAX_FETCH_BUFFER_LEN, "fecth");
			
			if (response != null) {
				displayFetchResults(response.getData());
				System.out.println("Fetch succeeeded.");
			} else {
				System.out.println("Fetch failed.");
			}
		} else if (cmdAndArgs[0].equals(UNREGISTER)) {
			// check if user passed in exactly 1 argument with "u" command
			if (cmdAndArgs.length != 2) {
				System.out.println("Unregister Usage: u <port num>");
				return;
			}

			int uPort = Integer.valueOf(cmdAndArgs[1]).intValue();
			
			// if the user tries to unregister a port that's never registered 
			// before, print the message to notify the user
			if (!reregHandlers.containsKey(uPort)) {
				System.out.printf("Port number %d hasn't been registered " + 
								  "yet.\n", uPort);
				return;
			}

			// build the unregister message
			ByteBuffer ubb = buildMsgHeader(U_MSG_TYPE, 10, seqNum);
			msg = buildUnregisterMsg(ubb, InetAddress.getLocalHost().getAddress(), uPort);
			seqNum++;
			
			DatagramPacket uReq = new DatagramPacket(msg, msg.length, 
													 hostName, servicePort);

			// send the request and waiting for response
			// expect an ACK as response
			fstSocket.send(uReq);
			response = requestForResponse(uReq, 4, "unregister");

			// indicate whether unregister succeeded or not
			if (response != null) {
				releasePort(uPort);
				System.out.println("Unregister succeeeded.");
			} else {
				System.out.println("Unregister failed.");
			}
		} else {
			// Since we've filtered out all the other possible inputs above, 
			// the only case we'll get to here is when user typed "p" command.
			// Check if user passed in no argument with "p" command
			if (cmdAndArgs.length != 1) {
				System.out.println("Probe Usage: p");
				return;
			}

			// build the probe message
			msg = (buildMsgHeader(P_MSG_TYPE, 4, seqNum)).array();
			DatagramPacket pReq = new DatagramPacket(msg, msg.length, 
													 hostName, servicePort);

			// send the request and waiting for response
			// expect an ACK as response
			fstSocket.send(pReq);
			response = requestForResponse(pReq, 4, "probe");

			// indicate whether probe succeeded or not
			if (response != null) {
				System.out.println("Yeah! Probed the service successfully.");
				seqNum++;
			} else {
				System.out.println("Probe failed.");
			}
		}
	}

	/**
	 * Add the specified port number from registration.
	 * 
	 * @param port port number
	 */
	private static synchronized void acquireLock(int port) {
		portLocks.put(port, true);
	}
	
	/**
	 * Return true if the port number is locked.
	 * 
	 * @return true if the port number is locked
	 */
	private static synchronized boolean getLockStatus(int port) {
		return portLocks.get(port);
	}
	
	/**
	 * Remove the specified port number from registration.
	 * 
	 * @param port port number
	 */
	private static synchronized void releasePort(int port) {
		portLocks.put(port, false);
	}

	/**
	 * Delete the port entry from the table.
	 * 
	 * @param port port number
	 */
	private static synchronized void removePort(int port) {
		portLocks.remove(port);
	}

	/**
	 * Build the header of the message to be sent.
	 * 
	 * @param msgType type of message to be sent
	 * @param len length of the message (in bytes)
	 * @param sNum sequence number
	 * @return a ByteBuffer contains special header, sequence number, and
	 * message type
	 */
	private static ByteBuffer buildMsgHeader(byte msgType, int len, byte sNum) {
		ByteBuffer bb = ByteBuffer.allocate(len).order(ByteOrder.BIG_ENDIAN);
		
		// put special 0xC461 header into message
		bb.put(SND_HEADER_BYTE);
		bb.put(FST_HEADER_BYTE);
		
		// put sequence number and message type into message
		bb.put(sNum);
		bb.put(msgType);

		return bb;
	}

	/**
	 * Build the register message.
	 * 
	 * @param bb a ByteBuffer to store all the information needed
	 * @param ip service IP
	 * @param port port number
	 * @param data service data
	 * @param name service name
	 * @param len service name length
	 * @return a byte array that contains the register message
	 */
	private static byte[] buildRegisterMsg(ByteBuffer bb, byte[] ip,
			int port, int data, String name, int len) {
		bb.put(ip);
		// convert port number to short to meet the format in the spec
		bb.putShort((short) port);
		bb.putInt(data);
		// convert service name length to byte to meet the format in the spec
		bb.put((byte) len);
		
		byte[] nameBytes = name.getBytes();
		bb.put(nameBytes);

		return bb.array();
	}

	/**
	 * Build the fetch message.
	 * 
	 * @param bb a ByteBuffer to store all the information needed
	 * @param name service name
	 * @param len service name length
	 * @return a byte array that contains the fetch message
	 */
	private static byte[] buildFetchMsg(ByteBuffer bb, String name, int len) {
		if (name != null) {
			bb.put((byte) len);
			bb.put(name.getBytes());
		}

		return bb.array();
	}

	/**
	 * Build the unregister message.
	 * 
	 * @param bb a ByteBuffer to store all the information needed
	 * @param ip service IP
	 * @param port port number
	 * @return a byte array that contains the unregister message
	 */
	private static byte[] buildUnregisterMsg(ByteBuffer bb, byte[] ip, int port) {
		bb.put(ip);
		// convert port number to short to meet the format in the spec
		bb.putShort((short) port);
		return bb.array();
	}

	/**
	 * Retrieve the life time of the registration from the service's response.
	 * 
	 * @param res service's response in byte array format
	 * @return life time of the registration
	 */
	private static int getLifeTime(byte[] res) {
		return ByteBuffer.wrap(res).order(ByteOrder.BIG_ENDIAN).getShort(4);
	}

	/**
	 * Print register succeed message.
	 * 
	 * @param ip service IP
	 * @param port service port number
	 * @param lifeTime life time of the registration
	 * @param rereg indicate if it is a register or reregister
	 */
	private static void printRegisterSucceedMsg(String ip, int port, 
											    int lifeTime, boolean rereg) {
		if (rereg) {
			System.out.print("Register ");
		} else {
			System.out.print("Reregister ");
		}
		System.out.println(ip + ":" + port + " succeed ");
		System.out.println("with lifetime = " + lifeTime);
	}

	/**
	 * Display the fetch results.
	 * 
	 * @param res data of the response
	 */
	private static void displayFetchResults(byte[] res) {
		int totalEntries = ByteBuffer.wrap(res).order(ByteOrder.BIG_ENDIAN).get(4);
		for (int i = 0; i < totalEntries; i++) {
			System.out.printf("Result %d:\n", i);
			displayFetchServiceIPs(res, i);
			displayFetchServicePorts(res, i);
			displayFetchServiceData(res, i);
		}
	}

	/**
	 * Helper function to display service IPs of the fetch results.
	 * 
	 * @param res data of the response
	 * @param idx position of the current entry
	 */
	private static void displayFetchServiceIPs(byte[] res, int idx) {
		byte[] ipBytes = new byte[4];
		String serviceIP;

		for (int i = 0; i < 4; i++) {
			ipBytes[i] = ByteBuffer.wrap(res).order(ByteOrder.BIG_ENDIAN).get((5 + i) + idx * 10);
		}
		
		try {
			serviceIP = InetAddress.getByAddress(ipBytes).getHostAddress(); 
			System.out.printf("    Service IP: %s\n", serviceIP);
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Helper function to display the service ports of fetch results.
	 * 
	 * @param res data of the response
	 * @param idx position of the current entry
	 */
	private static void displayFetchServicePorts(byte[] res, int idx) {
		short port = ByteBuffer.wrap(res).order(ByteOrder.BIG_ENDIAN).getShort(9 + idx * 10);
		if (port < 0)
			port += 65536;

		System.out.println("    Service Port: " + port);
	}
	
	/**
	 * Helper function to display the service data of fetch results.
	 * 
	 * @param res data of the response
	 * @param idx position of the current entry
	 */
	private static void displayFetchServiceData(byte[] res, int idx) {
		long mask = Long.parseLong("4294967296");
		long data = ByteBuffer.wrap(res).order(ByteOrder.BIG_ENDIAN).getInt(11 + idx * 10);

		long hexData = data;
		if (data < 0)
			hexData += mask;
 		
		System.out.print("    Service Data: 0x" + Long.toHexString(hexData).toLowerCase());
	}

	/**
	 * Helper function for sending request multiple times (if necessary) 
	 * and waiting for appropriate response.
	 * 
	 * @param req request to be sent
	 * @param bufLen length of the buffer for response
	 * @param msgType type of the request
	 * @throws IOException
	 */
	private static DatagramPacket requestForResponse(DatagramPacket req, 
			int bufLen, String msgType) throws IOException {
		// tries sending the packet at most MAX_NUM_TRIES times
		for (int i = 0; i < MAX_NUM_TRIES; i++) {
			byte[] buf = new byte[bufLen];
			DatagramPacket response = new DatagramPacket(buf, buf.length);
			try {
				fstSocket.receive(response);
				return response;
			} catch (SocketException e) {
				e.printStackTrace();
			} catch (SocketTimeoutException e) {
				System.out.println("Timed out waiting on response for " + 
								   msgType + " request.");
				fstSocket.send(req);
				continue;
			} catch (IOException e) {
				System.out.println("IO: " + e.getMessage());
			}
		}
		
		System.out.printf("No response received after %d tries.\n", MAX_NUM_TRIES);
		return null;
	}

	/**
	 * An inner class to handle reregistration.
	 */
	static class ReregHandler implements Runnable {
		private int reregInterval;
		private int port;
		private DatagramPacket rReq;

		public ReregHandler(DatagramPacket req, int p, int t) {
			rReq = req;
			port = p;
			reregInterval = t;
		}

		@Override
    	public void run() {
			try {
				Thread.sleep(1000 * reregInterval);
				// no need to reregister if the it's already unregistered
				if (!getLockStatus(port)) {
					removePort(port);
					reregHandlers.remove(port);
					return;
				}
				
				fstSocket.send(rReq);
				DatagramPacket response = requestForResponse(rReq, 6, "register");
				if (response == null) {
					System.out.println("Reregister failed.");
					releasePort(port);
					return;
				}

				// print the message of reregister succeed
				int lifeTime = getLifeTime(response.getData());
				String ip = InetAddress.getLocalHost().getHostAddress();
				printRegisterSucceedMsg(ip, port, lifeTime, true);
				
				int newReregInterval = 0;
				if (lifeTime >= 30)
					newReregInterval = lifeTime - 30;

				// done with reregister, create a new thread 
				// to do the next reregister if reregister is needed
				ReregHandler rrHandler = new ReregHandler(rReq, newReregInterval, port);
				Thread reregThread = new Thread(rrHandler);
				reregHandlers.put(port, reregThread);
				reregThread.start();
			} catch (InterruptedException e) {
				return;
			} catch (IOException e) {
				System.out.println("IO: " + e.getMessage());
			}
		}
	}

	/**
	 * An inner class to handle service's probes.
	 */
	static class ProbeHandler implements Runnable {
		@Override
    	public void run() {
			while (true) {
				byte[] buffer = new byte[4];
				DatagramPacket pReq = new DatagramPacket(buffer, buffer.length);

				try {
					sndSocket.receive(pReq);
					System.out.println("Ouch! Registration service porbed me!");
					
					// build the ask message with the sequence number 
					// sent from the service
					byte sNum = ByteBuffer.wrap(pReq.getData()).order(ByteOrder.BIG_ENDIAN).get(2);
					ByteBuffer abb = buildMsgHeader(ACK_MSG_TYPE, 4, sNum);
					byte[] msg = abb.array();
					DatagramPacket ack = new DatagramPacket(msg, msg.length, 
														    hostName, servicePort);
					sndSocket.send(ack);
				} catch (SocketException e) {
					break;
				} catch (IOException e) {
					System.out.println("IO: " + e.getMessage());
				}
			}
		}
	}
}
