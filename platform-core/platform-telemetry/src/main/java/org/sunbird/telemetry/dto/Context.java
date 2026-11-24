/**
 * 
 */
package org.sunbird.telemetry.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.util.List;
import java.util.Map;

/**
 * @author mahesh
 *
 */

@JsonInclude(Include.NON_NULL)
public class Context {

	public Context(String channel, String env, Producer pdata) {
		super();
		this.channel = channel;
		this.env = env;
		this.pdata = pdata;
	}
	
	private String channel;
	private Producer pdata;
	private String env;
	private String sid;
	private String did;
	private List<Map<String, Object>> cdata;
	
	
	/**
	 * @return the channel
	 */
	public String getChannel() {
		return channel;
	}
	/**
	 * @param channel the channel to set
	 */
	public void setChannel(String channel) {
		this.channel = channel;
	}
	/**
	 * @return the pdata
	 */
	public Producer getPdata() {
		return pdata;
	}
	/**
	 * @param pdata the pdata to set
	 */
	public void setPdata(Producer pdata) {
		this.pdata = pdata;
	}
	/**
	 * @return the env
	 */
	public String getEnv() {
		return env;
	}
	/**
	 * @param env the env to set
	 */
	public void setEnv(String env) {
		this.env = env;
	}
	/**
	 * @return the sid
	 */
	public String getSid() {
		return sid;
	}
	/**
	 * @param sid the sid to set
	 */
	public void setSid(String sid) {
		this.sid = sid;
	}
	/**
	 * @return the did
	 */
	public String getDid() {
		return did;
	}
	/**
	 * @param did the did to set
	 */
	public void setDid(String did) {
		this.did = did;
	}
	/**
	 * @return the cdata
	 */
	public List<Map<String, Object>> getCdata() {
		return cdata;
	}
	/**
	 * @param cdata the cdata to set
	 */
	public void setCdata(List<Map<String, Object>> cdata) {
		this.cdata = cdata;
	}
	
}
