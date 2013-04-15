/*
 * Copyright (c) 1998-2012 Caucho Technology -- all rights reserved
 *
 * This file is part of Resin(R) Open Source
 *
 * Each copy or derived work must preserve the copyright notice and this
 * notice unmodified.
 *
 * Resin Open Source is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * Resin Open Source is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE, or any warranty
 * of NON-INFRINGEMENT.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Resin Open Source; if not, write to the
 *
 *   Free Software Foundation, Inc.
 *   59 Temple Place, Suite 330
 *   Boston, MA 02111-1307  USA
 *
 * @author Scott Ferguson
 */

package org.ireland.jnetty.dispatch.servlet;

import com.caucho.util.L10N;

import javax.annotation.PostConstruct;
import javax.servlet.FilterChain;
import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.ireland.jnetty.config.ConfigException;
import org.ireland.jnetty.dispatch.ServletInvocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the servlets.
 * 
 * 管理Servlet创建\查找的Manager
 * 
 */
public class ServletManager
{
	static final Logger log = Logger.getLogger(ServletManager.class.getName());
	static final L10N L = new L10N(ServletManager.class);

	//<ServletName,ServletConfigImpl>
	private HashMap<String, ServletConfigImpl> _servlets = new HashMap<String, ServletConfigImpl>();

	private ArrayList<ServletConfigImpl> _servletList = new ArrayList<ServletConfigImpl>();


	public ServletManager()
	{
	}

	/**
	 * Adds a servlet to the servlet manager.
	 * 如果已经存在同名的ServletConfig,旧的会被覆盖
	 */
	public void addServlet(ServletConfigImpl config) throws ServletException
	{

		if (config.getServletContext() == null)
			throw new NullPointerException();

		config.setServletManager(this);

		synchronized (_servlets)
		{
			ServletConfigImpl mergedConfig = null;

			ServletConfigImpl existingConfig = _servlets.get(config.getServletName());

			if (existingConfig != null)
			{
				for (int i = _servletList.size() - 1; i >= 0; i--)
				{
					ServletConfigImpl oldConfig = _servletList.get(i);

					if (config.getServletName().equals(
							oldConfig.getServletName()))
					{
						_servletList.remove(i);
						break;
					}
				}

			}

			try
			{
				// ioc/0000, server/12e4
				if (mergedConfig == null)
					config.validateClass(false);
			} catch (ConfigException e)
			{
				throw e;
			} catch (Exception e)
			{
				if (log.isLoggable(Level.FINE))
					log.log(Level.FINE, e.toString(), e);
				else if (e instanceof ConfigException)
					log.config(e.getMessage());
				else
					log.config(e.toString());
			}

			if (mergedConfig == null)
			{
				_servlets.put(config.getServletName(), config);
				_servletList.add(config);
			}
		}
	}



	/**
	 * Returns ServletConfigImpl to the servlet manager.
	 */
	public ServletConfigImpl getServlet(String servletName)
	{
		return _servlets.get(servletName);
	}

	public HashMap<String, ServletConfigImpl> getServlets()
	{
		return _servlets;
	}

	/**
	 * Initialize servlets that need starting at server start.
	 */
	@PostConstruct
	public void init() throws ServletException
	{
		ArrayList<ServletConfigImpl> loadOnStartup = new ArrayList<ServletConfigImpl>();

		//取出loadOnStartup的Servlet,并按其数值升序排序
		for (int j = 0; j < _servletList.size(); j++)
		{
			ServletConfigImpl config = _servletList.get(j);

			if (config.getLoadOnStartup() == Integer.MIN_VALUE)
				continue;

			int i = 0;
			for (; i < loadOnStartup.size(); i++)
			{
				ServletConfigImpl config2 = loadOnStartup.get(i);

				if (config.getLoadOnStartup() < config2.getLoadOnStartup())
				{
					loadOnStartup.add(i, config);
					break;
				}
			}

			if (i == loadOnStartup.size())
				loadOnStartup.add(config);

		}

		//只实例化和初始化 loadOnStartup 的Servlet
		for (int i = 0; i < loadOnStartup.size(); i++)
		{
			ServletConfigImpl config = loadOnStartup.get(i);

			try
			{
				config.getInstance();
			} catch (ServletException e)
			{
				log.log(Level.WARNING, e.toString(), e);
			}
		}
	}

	/**
	 * Creates the servlet chain for the servlet.
	 * 根据ServletName和Invocation创建一个FilterChain
	 */
	public FilterChain createServletChain(String servletName, ServletInvocation invocation)
			throws ServletException
	{
		ServletConfigImpl config = _servlets.get(servletName);

		if (config == null)
		{
			throw new ServletException(L.l("'{0}' is not a known servlet.  Servlets must be defined by <servlet> before being used.",servletName));
		}

		if (invocation != null)
		{ // XXX: namedDispatcher
			if (!config.isAsyncSupported())
				invocation.clearAsyncSupported();

			invocation.setMultipartConfig(config.getMultipartConfig());
		}

		return config.createServletChain();
	}
	
	/**
	 * Creates the servlet chain for the servlet.
	 * 根据ServletConfigImpl和Invocation创建一个FilterChain
	 */
	public FilterChain createServletChain(ServletConfigImpl config, ServletInvocation invocation)
			throws ServletException
	{

		if (config == null)
		{
			throw new ServletException(
					L.l("'{0}' is not a known servlet.  Servlets must be defined by <servlet> before being used."));
		}

		if (invocation != null)
		{ // XXX: namedDispatcher
			if (!config.isAsyncSupported())
				invocation.clearAsyncSupported();

			invocation.setMultipartConfig(config.getMultipartConfig());

		}

		return config.createServletChain();
	}

	/**
	 * Instantiates a servlet given its configuration.
	 * 
	 * @param servletName
	 *            the servlet
	 * 
	 * @return the initialized servlet.
	 */
	public Servlet createServlet(String servletName) throws ServletException
	{
		ServletConfigImpl config = _servlets.get(servletName);

		if (config == null)
		{
			throw new ServletException(
					L.l("'{0}' is not a known servlet.  Servlets must be defined by <servlet> before being used.",
							servletName));
		}

		return config.getInstance();
	}

	/**
	 * Returns the servlet config.
	 */
	ServletConfigImpl getServletConfig(String servletName)
	{
		return _servlets.get(servletName);
	}

	public void destroy()
	{
		ArrayList<ServletConfigImpl> servletList;
		servletList = new ArrayList<ServletConfigImpl>();

		if (_servletList != null)
		{
			synchronized (_servletList)
			{
				servletList.addAll(_servletList);
			}
		}

		for (int i = 0; i < servletList.size(); i++)
		{
			ServletConfigImpl config = servletList.get(i);

			try
			{
				config.close();
			} catch (Throwable e)
			{
				log.log(Level.FINE, e.toString(), e);
			}
		}
	}
}