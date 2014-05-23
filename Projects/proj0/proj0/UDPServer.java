import java.net.*;
import java.util.Scanner;
import java.io.*;

/**
 * Simple UDP server program.
 * Mimicking the code from Socket tutorial provided on the course website and 
 * the example from http://www.kieser.net/linux/java_server.html
 * 
 * @author Chun-Wei Chen
 * @version 01/13/14
 */
public class UDPServer {
	private static DatagramSocket udpSocket = null;  // UDP socket
	private static InetAddress cAddr = null;  // client address
	private static int cPort;  // client port number
	
	public static void main (String args[]) {
		if (args.length != 1) {
			 System.out.println("Server Usage: java UDPServer <Port Number>");
			 System.exit(1);
		} else {
			// Server code
			try {
				// retrieve the port number passed in and create 
				// a UDP socket which binds to that port number
				int port = Integer.valueOf(args[0]).intValue();
				udpSocket = new DatagramSocket(port);
				
				// get the IPv4 address of the local host
				String ipAddr = InetAddress.getLocalHost().getHostAddress();
				
				// print the host address and the port number the UDP socket binds to
				System.out.printf("%s %s\n", ipAddr, port);
				
				// create another thread to handle the incoming request
				// (a.k.a print the client's address, port number and message sent)
				// and let the program keep accepting requests
				ServerInputHandler sih = new ServerInputHandler();
				Thread inputHandler = new Thread(sih);
				inputHandler.start();
				
				// create a scanner to read input from System.in, which will
				// be the reply to the client
				Scanner sc = new Scanner(System.in);
				
				// keeping reading until there is nothing to be read
				while (sc.hasNextLine()) {
					String next = sc.nextLine();
					
					if (cAddr != null) {
						// get the appropriate arguments and create a datagram packet for
						// replying to the client
						byte[] msg = next.getBytes();
						int mLen = next.length();
						DatagramPacket reply = new DatagramPacket(msg, mLen, cAddr, cPort);
					
						// reply to the client
						udpSocket.send(reply);
					}
				}
				
				// close the scanner when we are done with reading
				sc.close();
			} catch (NumberFormatException e) {
				System.out.println("NumberFormat: " + e.getMessage());
			} catch (SocketException e) {
				System.out.println("Socket: " + e.getMessage());
			} catch (UnknownHostException e) {
				System.out.println("UnknownHost: " + e.getMessage());
			} catch (IOException e) {
				System.out.println("IO: " + e.getMessage());
			} finally {
				// close the socket if the socket is created before the exception is thrown
				if (udpSocket != null)
					udpSocket.close();
			}
		}
	}
	
	/**
	 * Static inner class to handle the data from client while letting
	 * the main program send reply to the client. 
	 */
	static class ServerInputHandler implements Runnable {	
		@Override
		public void run() {
			// keep handling data until seeing EOF on stdin
			while (true) {
				byte[] buf = new byte[1024];  // buffer
				
				// create a DatagramPacket for handling requests
				DatagramPacket req = new DatagramPacket(buf, buf.length);
				try {
					// handling incoming request
					udpSocket.receive(req);
				
					if (cAddr == null || ! cAddr.equals(req.getAddress())) {
						// get the address and the port number of the client
						cAddr = req.getAddress();
						cPort = req.getPort();
					
						// print out client's address and port number
						System.out.println("[Contact from " + cAddr.getHostAddress() + ":" + cPort + "]");
					}
				
					// print the message the client sent
					String msg = new String(req.getData());
					System.out.println(msg);
				} catch (SocketException e) {
					break;  // terminate the thread when EOF is on stdin
				} catch (IOException e) {
					System.out.println("IO: " + e.getMessage());
				}
			}
		}
	}
}