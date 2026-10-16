package com.aidol.demo;

import java.io.IOException;
import java.util.Arrays;

/** Strict RIFF parser: never assumes a fixed WAV header size. */
public final class WavAudio {
    private static int u16(byte[] b, int p) { return (b[p] & 255) | ((b[p+1] & 255) << 8); }
    private static long u32(byte[] b, int p) { return (u16(b,p) & 65535L) | ((u16(b,p+2) & 65535L) << 16); }
    private static boolean tag(byte[] b,int p,String tag) {
        if(p+4>b.length) return false;
        for(int i=0;i<4;i++) if(b[p+i]!=(byte)tag.charAt(i)) return false;
        return true;
    }
    public static byte[] pcm(byte[] wav) throws IOException {
        if(wav.length<44 || !tag(wav,0,"RIFF") || !tag(wav,8,"WAVE") || u32(wav,4)+8 != wav.length)
            throw new IOException("回复音频格式不正确");
        boolean valid=false; byte[] pcm=null;
        for(int p=12;p+8<=wav.length;) {
            long n=u32(wav,p+4), end=p+8L+n;
            if(end>wav.length) throw new IOException("回复音频不完整");
            if(tag(wav,p,"fmt ")) {
                if(n<16 || u16(wav,p+8)!=1 || u16(wav,p+10)!=1 || u32(wav,p+12)!=24000 || u16(wav,p+22)!=16)
                    throw new IOException("回复音频采样格式不支持");
                valid=true;
            }
            if(tag(wav,p,"data")) pcm=Arrays.copyOfRange(wav,p+8,(int)end);
            p=(int)(end+(n&1));
        }
        if(!valid || pcm==null || pcm.length==0 || pcm.length%2!=0 || pcm.length>6*1024*1024)
            throw new IOException("回复音频为空或过长");
        return pcm;
    }
}
