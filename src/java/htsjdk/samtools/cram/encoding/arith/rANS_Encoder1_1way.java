package htsjdk.samtools.cram.encoding.arith;

import htsjdk.samtools.cram.encoding.arith.rans_byte.RansSymbol;
import htsjdk.samtools.cram.io.ByteBufferUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class rANS_Encoder1_1way {
	static int TF_SHIFT = 14;
	static int TOTFREQ = (1 << TF_SHIFT);

	static int BLK_SIZE = 1000000;

	// Room to allow for expanded BLK_SIZE on worst case compression.
	static int BLK_SIZE2 = ((int) (1.05 * BLK_SIZE));

	ByteBuffer rans_compress_O1(ByteBuffer in, ByteBuffer out_buf) {
		int in_size = in.remaining();
		if (out_buf == null)
			out_buf = ByteBuffer.allocate((int) (1.05 * in_size + 257 * 257 * 3 + 4));
		out_buf.position(1 + 4 + 4);

		int tab_size;
		RansSymbol[][] syms = new RansSymbol[256][256];
		for (int i = 0; i < syms.length; i++)
			for (int j = 0; j < syms[i].length; j++)
				syms[i][j] = new RansSymbol();

		ByteBuffer cp = out_buf.slice();

		int[][] F = new int[256][256], C = new int[256][256];
		int[] T = new int[256];
		int i, j;

		F = FrequencyTable.Order1_FT(in, TOTFREQ, 8, T);

		for (i = 0; i < 256; i++) {
			int x = 0;
			if (T[i] == 0)
				continue;

			cp.put((byte) i);
			for (j = 0; j < 256; j++) {
				C[i][j] = x;
				if (F[i][j] != 0) {
					// System.out.printf("F[%d][%d]=%d, x=%d\n", i, j, F[i][j],
					// x);
					x += F[i][j];

					cp.put((byte) j);
					cp.put((byte) (F[i][j] >> 8));
					cp.put((byte) (F[i][j] & 0xff));
					rans_byte.RansSymbolInit(syms[i][j], C[i][j], F[i][j]);
				}
			}
			cp.put((byte) 0);
			T[i] = x;
		}
		cp.put((byte) 0);
		tab_size = cp.position();
		assert (tab_size < 257 * 257 * 3);

		int rans7;
		rans7 = rans_byte.RansEncInit();

		ByteBuffer ptr = cp.slice();

		int i7 = in_size - 2;
		int l7 = in.get(i7 + 1);

		for (; i7 >= 0; i7--) {
			int c7 = 0xFF & in.get(i7);

			rans7 = rans_byte.RansEncPutSymbol(rans7, ptr, syms[c7][l7], TF_SHIFT);
			l7 = c7;
		}

		rans7 = rans_byte.RansEncPutSymbol(rans7, ptr, syms[0][l7], TF_SHIFT);

		ByteOrder byteOrder = out_buf.order();
		out_buf.order(ByteOrder.LITTLE_ENDIAN);
		ptr.putInt(rans7);
		ptr.flip();
		int cdata_size = ptr.limit();
		ByteBufferUtils.reverse(ptr);

		// Finalise block size and return it
		out_buf.limit(1 + 4 + 4 + tab_size + cdata_size);
		out_buf.put(0, (byte) 1);
		out_buf.putInt(1, out_buf.limit() - 5);
		out_buf.putInt(5, in_size);
		out_buf.order(byteOrder);
		out_buf.position(0);
		return out_buf;
	}

	private static void test(byte[] data) {
		ByteBuffer out = new rANS_Encoder1_1way().rans_compress_O1(ByteBuffer.wrap(data), null);
		byte[] output = new byte[out.limit()];
		out.get(output);
		System.out.println(Arrays.toString(output));
	}

	public static void main(String[] args) throws IOException {
		File inFile = new File(args[0]);
		File outFile = new File(args[1]);

		byte[] inBuf = new byte[BLK_SIZE];
		byte[] outBuf = new byte[inBuf.length * 2];
		ByteBuffer out = ByteBuffer.wrap(outBuf);
		InputStream is = new BufferedInputStream(new FileInputStream(inFile));
		OutputStream os = new BufferedOutputStream(new FileOutputStream(outFile));
		long start = System.nanoTime();
		while (true) {
			int len = is.read(inBuf);
			if (len == -1)
				break;
			out.clear();
			new rANS_Encoder1_1way().rans_compress_O1(ByteBuffer.wrap(inBuf, 0, len), out);
			os.write(outBuf, 0, out.limit());
		}
		os.close();
		is.close();
		long end = System.nanoTime();

		System.out.printf("Took %d microseconds, %5.1f MB/s\n", (end - start) / 1000, (double) inFile.length()
				/ ((end - start) / 1000f));
	}
}
