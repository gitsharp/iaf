/*
   Copyright 2013 Nationale-Nederlanden

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package nl.nn.adapterframework.http;

import java.io.IOException;
import java.net.SocketTimeoutException;

import javax.servlet.http.HttpServletResponse;

import jcifs.ntlmssp.NtlmFlags;
import jcifs.ntlmssp.Type1Message;
import jcifs.ntlmssp.Type2Message;
import jcifs.ntlmssp.Type3Message;
import jcifs.util.Base64;

import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.auth.AuthScheme;
import org.apache.http.auth.AuthSchemeFactory;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.NTLMEngine;
import org.apache.http.impl.auth.NTLMEngineException;
import org.apache.http.impl.auth.NTLMScheme;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.util.EntityUtils;

import nl.nn.adapterframework.configuration.ConfigurationException;
import nl.nn.adapterframework.core.HasPhysicalDestination;
import nl.nn.adapterframework.core.SenderException;
import nl.nn.adapterframework.core.SenderWithParametersBase;
import nl.nn.adapterframework.core.TimeOutException;
import nl.nn.adapterframework.parameters.ParameterResolutionContext;
import nl.nn.adapterframework.util.CredentialFactory;
import nl.nn.adapterframework.util.Misc;

/**
 * Sender that sends a message via a WebService based on NTLM authentication.
 *
 * <p><b>Configuration:</b>
 * <table border="1">
 * <tr><th>attributes</th><th>description</th><th>default</th></tr>
 * <tr><td>{@link #setUrl(String) url}</td><td>URL or base of URL to be used </td><td>&nbsp;</td></tr>
 * <tr><td>{@link #setContentType(String) contentType}</td><td>content-type of the request</td><td>text/html; charset=UTF-8</td></tr>
 * <tr><td>{@link #setSoapAction(String) soapAction}</td><td>the SOAPActionUri to be set in the requestheader</td><td>&nbsp;</td></tr>
 * <tr><td>{@link #setTimeout(int) timeout}</td><td>timeout in ms of obtaining a connection/result. 0 means no timeout</td><td>10000</td></tr>
 * <tr><td>{@link #setMaxConnections(int) maxConnections}</td><td>the maximum number of concurrent connections</td><td>10</td></tr>
 * <tr><td>{@link #setAuthAlias(String) authAlias}</td><td>alias used to obtain credentials for authentication to host</td><td>&nbsp;</td></tr>
 * <tr><td>{@link #setUserName(String) userName}</td><td>username used in authentication to host</td><td>&nbsp;</td></tr>
 * <tr><td>{@link #setPassword(String) password}</td><td>&nbsp;</td><td>&nbsp;</td></tr>
 * <tr><td>{@link #setProxyHost(String) proxyHost}</td><td>&nbsp;</td><td>&nbsp;</td></tr>
 * <tr><td>{@link #setProxyPort(int) proxyPort}</td><td>&nbsp;</td><td>80</td></tr>
 * </table></p>
 * 
 * @author  Peter Leeuwenburgh
 * @version $Id: WebServiceNtlmSender.java,v 1.4 2013/07/02 06:51:20 m168309 Exp $
 */
public class WebServiceNtlmSender extends SenderWithParametersBase implements
		HasPhysicalDestination {

	private String contentType = "text/xml; charset="
			+ Misc.DEFAULT_INPUT_STREAM_ENCODING;
	private String url;
	private int timeout = 10000;
	private int maxConnections=10;
	private String authAlias;
	private String userName;
	private String password;
	private String authDomain;
	private String proxyHost;
	private int proxyPort = 80;
	private String soapAction;

	private PoolingClientConnectionManager connectionManager;
	protected DefaultHttpClient httpClient;

	private final class JCIFSEngine implements NTLMEngine {
		private static final int TYPE_1_FLAGS = NtlmFlags.NTLMSSP_NEGOTIATE_56
				| NtlmFlags.NTLMSSP_NEGOTIATE_128
				| NtlmFlags.NTLMSSP_NEGOTIATE_NTLM2
				| NtlmFlags.NTLMSSP_NEGOTIATE_ALWAYS_SIGN
				| NtlmFlags.NTLMSSP_REQUEST_TARGET;

		public String generateType1Msg(final String domain,
				final String workstation) throws NTLMEngineException {
			final Type1Message type1Message = new Type1Message(TYPE_1_FLAGS,
					domain, workstation);
			return Base64.encode(type1Message.toByteArray());
		}

		public String generateType3Msg(final String username,
				final String password, final String domain,
				final String workstation, final String challenge)
				throws NTLMEngineException {
			Type2Message type2Message;
			try {
				type2Message = new Type2Message(Base64.decode(challenge));
			} catch (final IOException exception) {
				throw new NTLMEngineException("Invalid NTLM type 2 message",
						exception);
			}
			final int type2Flags = type2Message.getFlags();
			final int type3Flags = type2Flags
					& (0xffffffff ^ (NtlmFlags.NTLMSSP_TARGET_TYPE_DOMAIN | NtlmFlags.NTLMSSP_TARGET_TYPE_SERVER));
			final Type3Message type3Message = new Type3Message(type2Message,
					password, domain, username, workstation, type3Flags);
			return Base64.encode(type3Message.toByteArray());
		}
	}

	private class NTLMSchemeFactory implements AuthSchemeFactory {
		public AuthScheme newInstance(final HttpParams params) {
			return new NTLMScheme(new JCIFSEngine());
		}
	}

	public void configure() throws ConfigurationException {
		super.configure();

		HttpParams httpParameters = new BasicHttpParams();
		HttpConnectionParams.setConnectionTimeout(httpParameters, getTimeout());
		HttpConnectionParams.setSoTimeout(httpParameters, getTimeout());
		httpClient = new DefaultHttpClient(connectionManager, httpParameters);
		httpClient.getAuthSchemes().register("NTLM", new NTLMSchemeFactory());
		CredentialFactory cf = new CredentialFactory(getAuthAlias(),
				getUserName(), getPassword());
		httpClient.getCredentialsProvider().setCredentials(
				new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT),
				new NTCredentials(cf.getUsername(), cf.getPassword(), Misc
						.getHostname(), getAuthDomain()));
		if (StringUtils.isNotEmpty(getProxyHost())) {
			HttpHost proxy = new HttpHost(getProxyHost(), getProxyPort());
			httpClient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY,
					proxy);
		}
	}

	public void open() {
		connectionManager = new PoolingClientConnectionManager();
		connectionManager.setMaxTotal(getMaxConnections());
	}

	public void close() {
//		httpClient.getConnectionManager().shutdown();
		connectionManager.shutdown();
		connectionManager=null;
	}


	public String sendMessage(String correlationID, String message,
			ParameterResolutionContext prc) throws SenderException,
			TimeOutException {
		String result = null;
		HttpPost httpPost = new HttpPost(getUrl());
		try {
			StringEntity se = new StringEntity(message);
			httpPost.setEntity(se);
			if (StringUtils.isNotEmpty(getContentType())) {
				log.debug(getLogPrefix() + "setting Content-Type header ["
						+ getContentType() + "]");
				httpPost.addHeader("Content-Type", getContentType());
			}
			if (StringUtils.isNotEmpty(getSoapAction())) {
				log.debug(getLogPrefix() + "setting SOAPAction header ["
						+ getSoapAction() + "]");
				httpPost.addHeader("SOAPAction", getSoapAction());
			}
			log.debug(getLogPrefix() + "executing method");
			HttpResponse httpresponse = httpClient.execute(httpPost);
			log.debug(getLogPrefix() + "executed method");
			StatusLine statusLine = httpresponse.getStatusLine();
			if (statusLine == null) {
				throw new SenderException(getLogPrefix() + "no statusline found");
			} else {
				int statusCode = statusLine.getStatusCode();
				String statusMessage = statusLine.getReasonPhrase();
				if (statusCode == HttpServletResponse.SC_OK) {
					log.debug(getLogPrefix() + "status code [" + statusCode
							+ "] message [" + statusMessage + "]");
				} else {
					throw new SenderException(getLogPrefix() + "status code [" + statusCode
							+ "] message [" + statusMessage + "]");
				}
			}
			HttpEntity httpEntity = httpresponse.getEntity();
			if (httpEntity == null) {
				log.warn(getLogPrefix() + "no response found");
			} else {
				log.debug(getLogPrefix() + "response content length ["
						+ httpEntity.getContentLength() + "]");
				result = EntityUtils.toString(httpEntity);
				log.debug(getLogPrefix() + "retrieved result [" + result + "]");
			}
		} catch (Exception e) {
			if (e instanceof SocketTimeoutException) {
				throw new TimeOutException(e);
			} 
			throw new SenderException(e);
		} finally {
			httpPost.releaseConnection();
		}
		return result;
	}

	public String getPhysicalDestinationName() {
		return getUrl();
	}

	public void setContentType(String string) {
		contentType = string;
	}

	public String getContentType() {
		return contentType;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String string) {
		url = string;
	}

	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int i) {
		timeout = i;
	}

	public int getMaxConnections() {
		return maxConnections;
	}

	public void setMaxConnections(int i) {
		maxConnections = i;
	}

	public String getAuthAlias() {
		return authAlias;
	}

	public void setAuthAlias(String string) {
		authAlias = string;
	}

	public String getUserName() {
		return userName;
	}

	public void setUserName(String string) {
		userName = string;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String string) {
		password = string;
	}

	public String getAuthDomain() {
		return authDomain;
	}

	public void setAuthDomain(String string) {
		authDomain = string;
	}

	public String getProxyHost() {
		return proxyHost;
	}

	public void setProxyHost(String string) {
		proxyHost = string;
	}

	public int getProxyPort() {
		return proxyPort;
	}

	public void setProxyPort(int i) {
		proxyPort = i;
	}

	public String getSoapAction() {
		return soapAction;
	}

	public void setSoapAction(String string) {
		soapAction = string;
	}

}