import java.io.*;
import java.net.*;

/**
 * A simple HTTP Proxy which prints out the first line of each HTTP request 
 * it receives from the browser, then fetches the requested page from 
 * the sourcing web server and returns it to the browser.
 * 
 * @author Chun-Wei Chen
 * @version 02/15/14
 */
public class HTTPProxy {
	private static final String HTTP_END_LINE = "\r\n";
	private static final String EMPTY_LINE = "";
	private static final String CONNECTION_CLOSE = "Connection: close";
	private static final String CONNECTION_TAG = "connection:";
	private static final String HOST_TAG = "host:";
	private static final String CAP_HOST_TAG = "Host: ";

	/**
	 * Main method of the HTTP proxy that accept a port number
	 * as argument from the user.
	 * 
	 * @param args a array of command line arguments; the array should 
	 * contain only one argument that can be converted to 
	 * integer value in order to run the program
	 */
	public static void main(String[] args) {
		int port;
		if (args.length != 1) {
			System.out.println("Usage: java HTTPProxy <port number>");
			System.exit(1);
		}

		try {
			port = Integer.valueOf(args[0]).intValue();
			ProxyInitialization(port);
		} catch (NumberFormatException e) {
			System.out.println("NumberFormat: " + e.getMessage());
		}
	}

	/**
	 * Initialize the HTTP proxy.
	 * 
	 * @param port port number
	 * @throws IllegalArgumentException if the port parameter is outside the 
	 * specified range of valid port values, which is between 0 and 65535, inclusive
	 * @throws IOException if an I/O error occurs when opening the socket
	 * or waiting for a connection
	 */
	private static void ProxyInitialization(int port) {
		ServerSocket s = null;
		try {
			s = new ServerSocket(port);
			
			while (true) {
				// wait and accept a connection
				Socket s1 = s.accept();
				
				// create a separate thread to handle the client and 
				// keep accepting in-coming connections
				Thread cit = new Thread(new ClientHandler(s1));
				cit.start();
			}
		} catch (IllegalArgumentException e) {
			System.out.println("IllegalArgument: " + e.getMessage());
		} catch (IOException e) {
			System.out.println("IO: " + e.getMessage());
		}
	}

	static class ClientHandler implements Runnable {
		private Socket cSocket;

		/**
		 * Constructs a new ClientHandler.
		 * @param s a socket
		 * @throws IllegalArgumentException is s passed in is null
		 */
		public ClientHandler(Socket s) {
			if (s == null)
				throw new IllegalArgumentException("socket passed in cannot be null");

			cSocket = s;
		}

		/**
		 * Parses the HTTP request from the client.
		 */
		@Override
		public void run() {
			try {
				String host = null;
				int port = 80; // default port number

				// used to indicate the first line of request
				// for the proxy to print it
				int numOfLines;

				// HTTP request from client to server
				BufferedReader request = 
						new BufferedReader(new InputStreamReader(cSocket.getInputStream()));

				// get the first line of the request
				String reqLine = request.readLine();
				
				// response from the server to client
				DataOutputStream response = new DataOutputStream(cSocket.getOutputStream());

				// buffer to store the request from the client 
				// that will be sent to the server
				StringBuffer outputBuffer = new StringBuffer();

				for (numOfLines = 1; reqLine != null && reqLine.length() >= 1; 
					 numOfLines++) {

					// print the first line of each HTTP request
					if (numOfLines == 1)
						System.out.println(reqLine);

					// get rid of leading and trailing white spaces
					reqLine = reqLine.trim();

					// split the line into tokens by white spaces
					String[] reqLineParts = reqLine.split(" ");
					if ((reqLineParts[0].toLowerCase()).equals(CONNECTION_TAG)) {
						// turning off keep-alive
						reqLine = CONNECTION_CLOSE;
					} else if ((reqLineParts[0].toLowerCase()).equals(HOST_TAG)) {
						host = "";

						// retrieve the host from the HTTP 
						for (int i = 1; i < reqLineParts.length; i++)
							host += reqLineParts[i];

						// retrieve the port number from the HTTP, if specified
						String[] hostParts = host.split(":");
						if (hostParts.length == 2)
							port = Integer.valueOf(hostParts[1]).intValue();

						reqLine = CAP_HOST_TAG + host;
					}

					// append the request line just read to output buffer
					appendHTTPEndLine(outputBuffer, reqLine);

					// read the next line of the request
					reqLine = request.readLine();

					// cSocket.close(); // for experiment
					// cSocket.shutdownInput(); // for experiment
					// cSocket.shutdownOutput(); // for experiment
				}

				// append HTTP end of request indicator
				appendHTTPEndLine(outputBuffer, EMPTY_LINE);

				// the proxy only support the HTTP request with host tag
				if (host != null) {
					// send the request to server and send the 
					// response from server back to client
					fetchRequest(outputBuffer, response, host, port);
				}

				request.close();
			} catch (NumberFormatException e) {
				System.out.println("NumberFormat: " + e.getMessage());
			}  catch (IOException e) {
				System.out.println("IO: " + e.getMessage());
			}
		}

		/**
		 * Appends the HTTP end line indicator.
		 * 
		 * @param sb a StringBuffer contains partial HTTP request
		 * @param reqLine a line of the HTTP request
		 * @throws IllegalArgumentException if either one of the arguments
		 * passed in is null
		 */
		private void appendHTTPEndLine(StringBuffer sb, String reqLine) {
			if (sb == null || reqLine == null)
				throw new IllegalArgumentException("Arguments cannot be null.");
	
			if (reqLine.equals(EMPTY_LINE))
				sb.append(HTTP_END_LINE);
			else
				sb.append(reqLine + HTTP_END_LINE);
		}

		/**
		 * Fetches the requested page from the sourcing web server 
		 * and returns it to the browser.
		 * 
		 * @param sb a StringBuffer contains HTTP request
		 * @param res response from server to client
		 * @param host host name
		 * @param port port number
		 */
		private void fetchRequest(StringBuffer sb, DataOutputStream res, 
								  String host, int port) {
			try {
				Socket s2 = new Socket(host, port);
				InputStream serverResponse = s2.getInputStream();
				PrintWriter clientRequest = 
						new PrintWriter(new OutputStreamWriter(s2.getOutputStream()));

				// print out the request
				clientRequest.print(sb.toString());

				// flush the writer
				clientRequest.flush();

				// a buffer to hold the response from server 
				// and send to the client
				byte[] buf = new byte[32767];
				int numOfBytes = serverResponse.read(buf);

				// keep reading from the buffer until there's nothing to be read
				while (numOfBytes != -1) {
					// write the response to client
					// and flush the writer
					res.write(buf, 0, numOfBytes);
					res.flush();
					
					// read the next line of the response
					numOfBytes = serverResponse.read(buf);

					// cSocket.shutdownOutput(); // for experiment
					// cSocket.shutdownInput(); // for experiment
				}

				// done with sending request and getting response,
				// so close all the things we created for the connection
				clientRequest.close();
				serverResponse.close();
				s2.close();
				res.close();
			} catch (UnknownHostException e) {
				System.out.println("UnknownHost: " + e.getMessage());
			} catch (IOException e) {
				System.out.println("IO: " + e.getMessage());
			}
		}
	}
}