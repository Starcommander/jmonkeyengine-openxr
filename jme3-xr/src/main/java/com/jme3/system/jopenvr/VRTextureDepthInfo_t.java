package com.jme3.system.jopenvr;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import java.util.Arrays;
import java.util.List;
/**
 * <i>native declaration : headers\openvr_capi.h:1272</i><br>
 * This file was autogenerated by <a href="http://jnaerator.googlecode.com/">JNAerator</a>,<br>
 * a tool written by <a href="http://ochafik.com/">Olivier Chafik</a> that <a href="http://code.google.com/p/jnaerator/wiki/CreditsAndLicense">uses a few opensource projects.</a>.<br>
 * For help, please visit <a href="http://nativelibs4java.googlecode.com/">NativeLibs4Java</a> , <a href="http://rococoa.dev.java.net/">Rococoa</a>, or <a href="http://jna.dev.java.net/">JNA</a>.
 */
public class VRTextureDepthInfo_t extends Structure {
	/**
	 * void *<br>
	 * C type : void*
	 */
	public Pointer handle;
	/** C type : HmdMatrix44_t */
	public HmdMatrix44_t mProjection;
	/** C type : HmdVector2_t */
	public HmdVector2_t vRange;
	public VRTextureDepthInfo_t() {
		super();
	}
        @Override
	protected List<String> getFieldOrder() {
		return Arrays.asList("handle", "mProjection", "vRange");
	}
	/**
	 * @param handle void *<br>
	 * C type : void*<br>
	 * @param mProjection C type : HmdMatrix44_t<br>
	 * @param vRange C type : HmdVector2_t
	 */
	public VRTextureDepthInfo_t(Pointer handle, HmdMatrix44_t mProjection, HmdVector2_t vRange) {
		super();
		this.handle = handle;
		this.mProjection = mProjection;
		this.vRange = vRange;
	}
	public VRTextureDepthInfo_t(Pointer peer) {
		super(peer);
	}
	public static class ByReference extends VRTextureDepthInfo_t implements Structure.ByReference {
		
	};
	public static class ByValue extends VRTextureDepthInfo_t implements Structure.ByValue {
		
	};
}