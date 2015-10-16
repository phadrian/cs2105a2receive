import java.io.File;
import java.io.FileOutputStream;
import java.net.*;
import java.util.*;
import java.nio.*;
import java.util.zip.*;

public class FileReceiver {

	// Format of the packet
	// <CRC>-------<sequence number>----<data length>----<data>
	// <8bytes>----<4bytes>-------------<4bytes>---------<984bytes>
	
	public static void main(String[] args) throws Exception 
	{
		if (args.length != 1) {
			System.err.println("Usage: FileReceiver <port>");
			System.exit(-1);
		}
		
		// Declare the size of the packet buffer
		int bufferSize = 1000;
				
		// Create a socket and set it to listen to a port
		int port = Integer.parseInt(args[0]);
		DatagramSocket socket = new DatagramSocket(port);
		
		// Create CRC32 object to calculate checksums
		CRC32 crc = new CRC32();
				
		/*
		 * Create and receive the filepath packet
		 * ========================================
		 */
		
		// Create the packet
		byte[] data = new byte[bufferSize];
		ByteBuffer b = ByteBuffer.wrap(data);
		DatagramPacket pathPacket = new DatagramPacket(data, data.length);
		
		// Receive the packet
		socket.receive(pathPacket);
		
		// Get checksum
		long checksum = b.getLong();
		
		// Get the sequence number and number of data bytes
		int seq = b.getInt();
		int numOfDataBytes = b.getInt();
		
		// Calculate the checksum of the data in the packet
		crc.reset();
		crc.update(data, Long.BYTES, pathPacket.getLength() - Long.BYTES);
		
		// Debug message for filepath packet
		System.out.println("Receiving filepath packet.");
		System.out.println("Received CRC:" + checksum + " Data:" + bytesToHex(data, pathPacket.getLength()));
		
		/*
		 * Check if the filepath packet was received correctly
		 * =====================================================
		 */
		
		boolean isCorrupted = (checksum != crc.getValue()) || (seq != 0);
		
		if (isCorrupted) {
			while (isCorrupted) {
				// Send NAK
				System.out.println("Packet corrupted, requesting resend.");
				byte[] nakByte = "corrupted".getBytes();
				DatagramPacket nak = new DatagramPacket(
						nakByte, 0, nakByte.length, pathPacket.getSocketAddress());

				socket.send(nak);

				// Receive packet again
				data = new byte[bufferSize];
				b = ByteBuffer.wrap(data);
				pathPacket = new DatagramPacket(data, data.length);
				socket.receive(pathPacket);

				checksum = b.getLong();

				// Calculate the checksum of the data in the packet
				crc.reset();
				crc.update(data, Long.BYTES, pathPacket.getLength() - Long.BYTES);
				
				// Get the sequence number and number of data bytes
				seq = b.getInt();
				numOfDataBytes = b.getInt();

				// Debug message for filepath packet
				System.out.println("Receiving filepath packet.");
				System.out.println("Received CRC:" + checksum + " Data:" + bytesToHex(data, pathPacket.getLength()));
				
				// Verify the packet again
				isCorrupted = (checksum != crc.getValue()) || (seq != 0);
				
				// Send ACK if the packet has been verified and exit the loop
				if (!isCorrupted) {
					System.out.println("Packet verified.");
					byte[] ackByte = "notCorrupted".getBytes();
					DatagramPacket ack = new DatagramPacket(
							ackByte, 0, ackByte.length, pathPacket.getSocketAddress());
					
					socket.send(ack);
				}
			}
		} else {
			// Send ACK if the packet has been verified
			if (!isCorrupted) {
				System.out.println("Packet verified.");
				byte[] ackByte = "notCorrupted".getBytes();
				DatagramPacket ack = new DatagramPacket(
						ackByte, 0, ackByte.length, pathPacket.getSocketAddress());
				
				socket.send(ack);
			}
		}
		
		/*
		 * Continue with the rest of the data
		 * ====================================
		 */
		
		// Get the filepath in bytes and convert to String
		byte[] destBytes = new byte[numOfDataBytes];
		b.get(destBytes);
		
		// Run trim() to remove whitespaces from the empty bits of data
		String dest = new String(destBytes).trim();

		/*
		 * Write the received bytes into file on the disk
		 * ================================================
		 */
		
		// Create a FileOutputStream to write the file
		File file = new File(dest);
		FileOutputStream fileOut = new FileOutputStream(file, true);
		
		// Create the data packet to receive into
		data = new byte[bufferSize];
		b = ByteBuffer.wrap(data);
		DatagramPacket dataPacket = new DatagramPacket(data, data.length);
		
		// Initialize a counter to check sequence number with
		int packetIndex = 1;
		
		while(true) {
			
			// Receive the data packet
			socket.receive(dataPacket);
			
			// Display length error if packet length < checksum length(incomplete packet)
			if (dataPacket.getLength() < Long.BYTES) {
				System.out.println("Packet too short");
				continue;
			}
			
			// Calculate the checksum of the data in the packet
			b.rewind();
			checksum = b.getLong();
			crc.reset();
			crc.update(data, Long.BYTES, dataPacket.getLength() - Long.BYTES);
			
			// Get the sequence number and number of data bytes
			seq = b.getInt();
			numOfDataBytes = b.getInt();
			
			// Debug output
			System.out.println("Received CRC:" + crc.getValue() + " Data:" + bytesToHex(data, dataPacket.getLength()));
			
			/*
			 * Check if the packet is corrupted
			 * ==================================
			 */
			
			// Retransmissions occur when FileSender receives a garbled ACK/NAK from FileReceiver
			boolean isRetransmission = (crc.getValue() == checksum) && seq == packetIndex - 1;
			isCorrupted = (crc.getValue() != checksum) || (seq != packetIndex);
			
			if (isCorrupted) {
				while (isCorrupted) {
					// If it is a retransmission
					if (isRetransmission) {
						// Send FileSender an ACK response until it sends a new data packet
						// (Send until the ACK gets through)
						System.out.println("Retransmitted packet, resending ACK.");
						byte[] ackByte = "notCorrupted".getBytes();
						DatagramPacket ack = new DatagramPacket(
								ackByte, 0, ackByte.length, pathPacket.getSocketAddress());

						socket.send(ack);
					} else { // If the data is actually corrupt, not a retransmission
						// Send NAK
						System.out.println("Packet corrupted, requesting resend.");
						byte[] nakByte = "corrupted".getBytes();
						DatagramPacket nak = new DatagramPacket(
								nakByte, 0, nakByte.length, dataPacket.getSocketAddress());

						socket.send(nak);
					}
					
					// Receive the packet again
					data = new byte[bufferSize];
					b = ByteBuffer.wrap(data);
					dataPacket = new DatagramPacket(data, data.length);
					socket.receive(dataPacket);

					checksum = b.getLong();

					// Get the sequence number and number of data bytes
					seq = b.getInt();
					numOfDataBytes = b.getInt();

					// Calculate the checksum of the data in the packet
					crc.reset();
					crc.update(data, Long.BYTES, dataPacket.getLength() - Long.BYTES);

					// Debug message for data packet
					System.out.println("Receiving data packet.");
					System.out.println("Received CRC:" + checksum + " Data:" + bytesToHex(data, dataPacket.getLength()));

					// Verify the packet again
					isRetransmission = (crc.getValue() == checksum) && seq == packetIndex - 1;
					isCorrupted = (crc.getValue() != checksum) || (seq != packetIndex);

					// Send ACK if the packet has been verified and not a retransmission
					if (!isCorrupted && !isRetransmission) {
						System.out.println("Packet verified.");
						byte[] ackByte = "notCorrupted".getBytes();
						DatagramPacket ack = new DatagramPacket(
								ackByte, 0, ackByte.length, pathPacket.getSocketAddress());

						socket.send(ack);

						// Write the data to file
						byte[] readBytes = new byte[numOfDataBytes];
						b.get(readBytes);
						fileOut.write(readBytes);

						// Increment the packet index
						packetIndex++;
					}
				}
			} else {
				// Send ACK if the packet has been verified
				if (!isCorrupted && !isRetransmission) {
					System.out.println("Packet verified.");
					byte[] ackByte = "notCorrupted".getBytes();
					DatagramPacket ack = new DatagramPacket(
							ackByte, 0, ackByte.length, pathPacket.getSocketAddress());
					
					socket.send(ack);
				}
				
				// Write the data to file
				byte[] readBytes = new byte[numOfDataBytes];
				b.get(readBytes);
				fileOut.write(readBytes);
				
				// Increment the packet index
				packetIndex++;
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
