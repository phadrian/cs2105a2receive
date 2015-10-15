import java.io.File;
import java.io.FileOutputStream;
import java.net.*;
import java.util.*;
import java.nio.*;
import java.util.zip.*;

public class FileReceiver {

	public static void main(String[] args) throws Exception 
	{
		if (args.length != 1) {
			System.err.println("Usage: FileReceiver <port>");
			System.exit(-1);
		}
		
		// Create a socket and set it to listen to a port
		int port = Integer.parseInt(args[0]);
		DatagramSocket socket = new DatagramSocket(port);
		
		// Create CRC32 object to calculate checksums
		CRC32 crc = new CRC32();
				
		// Create the filepath packet
		byte[] data = new byte[1000];
		ByteBuffer b = ByteBuffer.wrap(data);
		DatagramPacket pathPacket = new DatagramPacket(data, data.length);
		
		// Receive the packet
		socket.receive(pathPacket);
		
		// Assume checksum is correct
		// Calculate the checksum of the data in the packet
		b.rewind();
		long checksum = b.getLong();
		crc.reset();
		crc.update(data, 8, pathPacket.getLength() - 8);
		
		// Debug message for filepath packet
		System.out.println("Received CRC:" + checksum + " Data:" + bytesToHex(data, pathPacket.getLength()));
		
		// Get the filepath in bytes and convert to String
		int numOfDataBytes = data.length - 8;
		byte[] destBytes = new byte[numOfDataBytes];
		b.get(destBytes);
		// Run trim() to remove whitespaces from the empty bits of data
		String dest = new String(destBytes).trim();

		// Create a FileOutputStream to write the file
		File file = new File(dest);
		FileOutputStream fileOut;
		
		if (file.exists()) {
			// Overwrite the file if it exists before program run
			fileOut = new FileOutputStream(file);
		} else {
			fileOut = new FileOutputStream(file, true);
		}
		
		// Create the data packet
		data = new byte[1000];
		b = ByteBuffer.wrap(data);
		DatagramPacket dataPacket = new DatagramPacket(data, data.length);	
		
		while(true) {
			// Receive the data packet
			socket.receive(dataPacket);
			
			// Display length error if packet length < checksum length(incomplete packet)
			if (dataPacket.getLength() < 8) {
				System.out.println("Packet too short");
				continue;
			}
			
			// Calculate the checksum of the data in the packet
			b.rewind();
			checksum = b.getLong();
			crc.reset();
			crc.update(data, 8, dataPacket.getLength() - 8);
			
			// Debug output
			System.out.println("Received CRC:" + crc.getValue() + " Data:" + bytesToHex(data, dataPacket.getLength()));
			
			if (crc.getValue() != checksum) {
				System.out.println("Packet corrupted");
				
				// If the packet is corrupt, request a resend
				byte[] nakByte = new byte[1];
				nakByte[0] = 0;
				DatagramPacket nak = new DatagramPacket(
						nakByte, 0, 1, dataPacket.getSocketAddress());
				
				socket.send(nak);
			} else {
				
				// Create a byte[] to store the data
				byte[] readBytes = new byte[numOfDataBytes];
				b.get(readBytes);
				
				fileOut.write(readBytes, 0, dataPacket.getLength() - 8);
				
				byte[] ackByte = new byte[1];
				ackByte[0] = 1;
				DatagramPacket ack = new DatagramPacket(
						ackByte, 0, 1, dataPacket.getSocketAddress());
				
				socket.send(ack);
			}	
		}
	}

	final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();
	public static String bytesToHex(byte[] bytes, int len) {
	    char[] hexChars = new char[len * 2];
	    for ( int j = 0; j < len; j++ ) {
	        int v = bytes[j] & 0xFF;
	        hexChars[j * 2] = hexArray[v >>> 4];
	        hexChars[j * 2 + 1] = hexArray[v & 0x0F];
	    }
	    return new String(hexChars);
	}
}
