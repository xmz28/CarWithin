package com.example.carwithin;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** Independently implemented from the Android TL-DASH-V2 wire capture. */
public final class TailingProtocol {
    private TailingProtocol() {}
    public record Message(int command,byte[] payload) {}
    public record Auxiliary(int command,int token,byte[] payload) {}
    public static Auxiliary readAuxiliary(InputStream in)throws IOException {
        ByteBuffer b=ByteBuffer.wrap(exact(in,8)).order(ByteOrder.LITTLE_ENDIAN);
        int command=b.getShort()&65535,size=b.getShort()&65535,token=b.getInt();
        if(size>8192)throw new IOException("台铃配套帧超出支持长度");
        return new Auxiliary(command,token,exact(in,size));
    }
    public static byte[] auxiliary(int command,int token,byte[] payload) {
        int size=payload==null?0:payload.length;
        if(size>8192)throw new IllegalArgumentException("配套帧过长");
        ByteBuffer b=ByteBuffer.allocate(8+size).order(ByteOrder.LITTLE_ENDIAN).putShort((short)command).putShort((short)size).putInt(token);
        if(size>0)b.put(payload);return b.array();
    }
    public static byte[] message(int command,String json) {
        byte[] payload=json==null?new byte[0]:json.getBytes(StandardCharsets.UTF_8);
        int size=16+payload.length;
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).putInt(command).putInt(size)
                .putInt(command^size).putInt(0).put(payload).array();
    }
    public static Message read(InputStream in)throws IOException {
        return read(in,false);
    }
    static Message read(InputStream in,boolean allowInstrumentTag)throws IOException {
        byte[] h=exact(in,16);ByteBuffer b=ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        int command=b.getInt(),size=b.getInt(),check=b.getInt(),reserved=b.getInt();
        if(size<16||size>65536||check!=(command^size)||(!allowInstrumentTag&&reserved!=0))
            throw new IOException("PXC 控制帧格式不匹配，命令 0x"+Integer.toHexString(command));
        return new Message(command,exact(in,size-16));
    }
    public static byte[] exact(InputStream in,int size)throws IOException {
        byte[] b=new byte[size];int offset=0;
        while(offset<size){int n=in.read(b,offset,size-offset);if(n<0)throw new EOFException("仪表连接已关闭");offset+=n;}
        return b;
    }
    public static void writeJpeg(OutputStream out,byte[] jpeg)throws IOException {
        if(jpeg.length<4||jpeg[0]!=(byte)255||jpeg[1]!=(byte)216||jpeg[jpeg.length-2]!=(byte)255||jpeg[jpeg.length-1]!=(byte)217)
            throw new IOException("JPEG帧不完整");
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(jpeg.length).array());out.write(jpeg);out.flush();
    }
    public static boolean feedback(byte[] b){return b.length==8&&ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getLong()==0x72;}
    public static int auxiliarySize(byte[] head)throws IOException {
        if(head.length!=8)throw new IOException("台铃配套通道头部长度不符");
        ByteBuffer h=ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);int packed=h.getInt(),reserved=h.getInt(),size=packed>>>16;
        if((packed&65535)!=16||reserved!=0||size<32||size>4096)throw new IOException("台铃配套通道头部与协议不符");
        return size;
    }
}
