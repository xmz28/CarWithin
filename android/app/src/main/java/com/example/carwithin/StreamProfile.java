package com.example.carwithin;

public record StreamProfile(int width, int height, int fps, int bitrate, int rotation, Scale scale, Codec codec, int jpegQuality, Transport transport) {
    /** Temporary product gate. Set true to restore Ninebot 4.3 UI, preset and saved profiles. Protocol sources stay intact. */
    public static final boolean NINEBOT_JPEG43_ENABLED = true;
    public boolean isAvailable(){return NINEBOT_JPEG43_ENABLED || codec!=Codec.JPEG || transport==Transport.TAILING_TCP;}
    public enum Codec { H264, JPEG }
    public enum Scale { FIT, CROP, STRETCH }
    /** DIRECT sends the private RTP straight to UDP 5012 (five-inch / 4.3-inch). KCP wraps it in
     *  the ikcp conv=3 reliable layer the seven-inch meter requires on the same port.
     *  EASYCONN_TCP uses generic EasyConn / PXC reverse TCP (10930 probe + 10922/10921/10920)
     *  with runtime resolution negotiation (e.g. QJMotor Sai600 HYTY75-1025 800x362). */
    public enum Transport { DIRECT, KCP, TAILING_TCP, ZEEHO_TCP, EASYCONN_TCP }
    public StreamProfile(int width,int height,int fps,int bitrate,int rotation,Scale scale) {
        this(width,height,fps,bitrate,rotation,scale,Codec.H264,80,Transport.DIRECT);
    }
    public StreamProfile(int width,int height,int fps,int bitrate,int rotation,Scale scale,Codec codec,int jpegQuality) {
        this(width,height,fps,bitrate,rotation,scale,codec,jpegQuality,Transport.DIRECT);
    }
    public static StreamProfile defaults() {
        return h264(848,480);
    }
    /** Seven-inch iOS capture: 1024×600 Baseline L3.1 carried over KCP (conv=3). Nominal 25 FPS from
     *  the decoder; bitrate still unverified so it keeps the shared 2 Mbps default. */
    public static StreamProfile sevenInch() {
        return new StreamProfile(1024,600,25,2000000,0,Scale.FIT,Codec.H264,80,Transport.KCP);
    }
    private static StreamProfile h264(int width, int height) {
        return new StreamProfile(width,height,20,2000000,0,Scale.FIT);
    }
    public static StreamProfile jpeg43(){return new StreamProfile(480,272,10,1500000,0,Scale.FIT,Codec.JPEG,80);}
    public static StreamProfile tailing43(){return new StreamProfile(480,272,20,1500000,0,Scale.FIT,Codec.JPEG,80,Transport.TAILING_TCP);}
    public static StreamProfile zeeho62(){return new StreamProfile(1024,480,25,2500000,0,Scale.FIT,Codec.H264,80,Transport.ZEEHO_TCP);}
    /** QJMotor Sai600 (HYTY75-1025) / Generic EasyConn PXC: runtime dimensions negotiated over TCP 10921 (default 800x362). */
    public static StreamProfile easyConnDefault(){return new StreamProfile(800,362,25,2500000,0,Scale.FIT,Codec.H264,80,Transport.EASYCONN_TCP);}
    public StreamProfile withNegotiated(int nextWidth, int nextHeight, int nextFps) {
        int clampedFps = Math.max(5, Math.min(30, nextFps));
        return new StreamProfile(nextWidth, nextHeight, clampedFps, bitrate, rotation, scale, codec, jpegQuality, transport);
    }
    public String label(){return transport==Transport.EASYCONN_TCP?"钱江/亿连 PXC H.264 / TCP":transport==Transport.ZEEHO_TCP?"极核 6.2 寸 H.264 / TCP":transport==Transport.TAILING_TCP?"台铃 4.3 寸 JPEG / TCP":codec==Codec.JPEG?"九号 4.3 寸 JPEG（测试）":transport==Transport.KCP?"九号 7 寸 H.264 / KCP（实验）":"九号 H.264";}
    public String encode(){return width+","+height+","+fps+","+bitrate+","+rotation+","+scale.ordinal()+","+codec.ordinal()+","+jpegQuality+","+transport.ordinal();}
    public static StreamProfile decode(String text) {
        String[] v=text.split(",");
        if(v.length!=6 && v.length!=8 && v.length!=9)throw new IllegalArgumentException("配置字段不完整");
        return new StreamProfile(Integer.parseInt(v[0]),Integer.parseInt(v[1]),Integer.parseInt(v[2]),Integer.parseInt(v[3]),Integer.parseInt(v[4]),Scale.values()[Integer.parseInt(v[5])],v.length>=8?Codec.values()[Integer.parseInt(v[6])]:Codec.H264,v.length>=8?Integer.parseInt(v[7]):80,v.length==9?Transport.values()[Integer.parseInt(v[8])]:Transport.DIRECT);
    }
    public boolean requiresLevel4() {
        return avcLevel()>=40;
    }
    public int avcLevel() {
        int w=(width+15)/16,h=(height+15)/16,mb=w*h;
        if(mb<=1620 && mb*fps<=40500 && w*w<=1620*8 && h*h<=1620*8)return 30;
        if(mb<=3600 && mb*fps<=108000 && w*w<=3600*8 && h*h<=3600*8)return 31;
        return 40;
    }
    public StreamProfile {
        if(transport==Transport.ZEEHO_TCP && (codec!=Codec.H264 || width!=1024 || height!=480))
            throw new IllegalArgumentException("极核 TCP 模式需要 1024×480 H.264");
        if(transport==Transport.EASYCONN_TCP && codec!=Codec.H264)
            throw new IllegalArgumentException("亿连 PXC TCP 模式仅支持 H.264");
        if(transport==Transport.TAILING_TCP && codec!=Codec.JPEG)throw new IllegalArgumentException("台铃TCP模式仅支持JPEG");
        if(width<64||height<64||width>1920||height>1920||(long)width*height>2073600||width%2!=0||height%2!=0)
            throw new IllegalArgumentException("宽高须为64–1920之间的偶数，总像素不超过1920×1080");
        if(fps<5||fps>30||bitrate<250000||bitrate>8000000||rotation%90!=0||rotation<0||rotation>270||scale==null||codec==null||transport==null||jpegQuality<30||jpegQuality>95)
            throw new IllegalArgumentException("帧率5–30，码率250–8000kbps，旋转0/90/180/270度");
    }
    /** Output placement in display coordinates, independent of Android so scaling can be tested. */
    public float[] rect(int sourceWidth,int sourceHeight) {
        if(sourceWidth<=0||sourceHeight<=0)throw new IllegalArgumentException("源尺寸无效");
        int w=rotation%180==0?sourceWidth:sourceHeight,h=rotation%180==0?sourceHeight:sourceWidth;
        if(scale==Scale.STRETCH)return new float[]{0,0,width,height};
        float ratio=scale==Scale.FIT?Math.min((float)width/w,(float)height/h):Math.max((float)width/w,(float)height/h);
        float rw=w*ratio,rh=h*ratio;return new float[]{(width-rw)/2,(height-rh)/2,(width+rw)/2,(height+rh)/2};
    }
}
