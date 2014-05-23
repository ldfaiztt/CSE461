import java.net.*;
import java.util.Scanner;
import java.io.*;

/**
 * Simple UDP client program.
 * Mimicking the code Socket tutorial provided on the course website and 
 * the example from http://www.kieser.net/linux/java_server.html
 * 
 * @author Chun-Wei Chen
 * @version 01/13/14
 */
public class UDPClient {
	private static DatagramSocket udpSocket = null;  // UDP socket
	private static InetAddress sAddr = null;  // server address
	private static int sPort;  // server port number
	
	public static void main (String args[]) {
		if (args.length != 2) {
			 System.out.println("Client Usage: java UDPClient <Host Name> <Port Number>");
			 System.exit(1);
		} else {
			// Client code
			try {
				udpSocket = new DatagramSocket();
				
				// get the server address using the host name passed in and
				// get the port number passed in 
				sAddr = InetAddress.getByName(args[0]);
				sPort = Integer.valueOf(args[1]).intValue();
				
				// get the IPv4 address of the local host and port number
				String ipAddr = InetAddress.getLocalHost().getHostAddress();
				int port = udpSocket.getLocalPort();
				
				// send to the destination a packet containing IP address of this client
				// and the port number
				String addrAndPort = ipAddr + " " + port;
				byte[] apMsg = addrAndPort.getBytes();
				int apLen = addrAndPort.length();
				DatagramPacket apPacket = new DatagramPacket(apMsg, apLen, sAddr, sPort);
				udpSocket.send(apPacket);
				
				// create another thread to handle the reply
				// and let the program keep sending requests
				ClientInputHandler cih = new ClientInputHandler();
				Thread inputHandler = new Thread(cih);
				inputHandler.start();
				
				// create a scanner to read input from System.in, which will
				// be the request to the server
				Scanner sc = new Scanner(System.in);
				
				// keeping reading until there is nothing to be read
				while (sc.hasNextLine()) {
					String next = sc.nextLine();
					
					if (sAddr != null) {
						// get the appropriate arguments and create a datagram packet for
						// requesting to the server
						byte[] msg = next.getBytes();
						int mLen = next.length();
						DatagramPacket req = new DatagramPacket(msg, mLen, sAddr, sPort);
					
						// request to the server
						udpSocket.send(req);
					}
				}
				
				// close the scanner when we are done with reading
				sc.close();
			} catch (SocketException e) {
				System.out.println("Socket: " + e.getMessage());
			} catch (UnknownHostException e) {
				System.out.println("UnknownHost: " + e.getMessage());
			} catch (IOException e) {
				System.out.println("IO: " + e.getMessage());
			} finally {
				if (udpSocket != null)
					udpSocket.close();
			}
		}
	}
	
	/**
	 * Static inner class to handle the data from server while letting
	 * the main program send request to the server. 
	 */
	static class ClientInputHandler implements Runnable {	
		@Override
		public void run() {
			// keep handling data until seeing EOF on stdin
			while (true) {
				byte[] buf = new byte[1024];  // buffer

				// create a DatagramPacket for handling incoming data
				DatagramPacket reply = new DatagramPacket(buf, buf.length);
				try {
					// handling incoming data
					udpSocket.receive(reply);
				
					// print the message the server sent
					String msg = new String(reply.getData());
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