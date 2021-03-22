/*
 * The MIT License
 *
 * (c) 2004-2015. Parallels IP Holdings GmbH. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.parallels.desktopcloud;

import hudson.model.Node;
import hudson.model.Computer;
import hudson.remoting.Channel;
import hudson.security.Permission;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.OfflineCause;
import jenkins.model.Jenkins;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.JMException;
import jenkins.security.MasterToSlaveCallable;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import org.apache.commons.validator.routines.InetAddressValidator;


public class ParallelsDesktopConnectorSlaveComputer extends AbstractCloudComputer<ParallelsDesktopConnectorSlave>
{
	private static final ParallelsLogger LOGGER = ParallelsLogger.getLogger("PDConnectorSlaveComputer");
	private static final int TIMEOUT = 180;
	private int numSlavesRunning = 0;
	private VMResources hostResources;
	
	private final int maxConfiguredSlaves;
	private final boolean useLinkedClones;

	public ParallelsDesktopConnectorSlaveComputer(ParallelsDesktopConnectorSlave slave)
	{
		super(slave);
		
		this.maxConfiguredSlaves = slave.getOwner().getMaxConcurrentVms();
		this.useLinkedClones = slave.getOwner().getUseLinkedClones();
	}

	private String getVmIPAddress(String vmId) throws Exception
	{
		InetAddressValidator validator = InetAddressValidator.getInstance();
		for (int i = 0; i < TIMEOUT; ++i)
		{
			RunVmCallable command = new RunVmCallable("list", "-f", "--json", vmId);
			String callResult = forceGetChannel().call(command);
			LOGGER.log(Level.SEVERE, " - (%d/%d) calling for IP. Result: %s", i, TIMEOUT, callResult);
			JSONArray vms = (JSONArray)JSONSerializer.toJSON(callResult);
			JSONObject vmInfo = vms.getJSONObject(0);
			String ip = vmInfo.getString("ip_configured");
			if (validator.isValidInet4Address(ip))
				return ip;
			Thread.sleep(1000);
		}
		throw new Exception("Failed to get IP for VM '" + vmId + "'");
	}

	private JSONObject getVMInfo(String vmId) throws Exception
	{
		RunVmCallable command = new RunVmCallable("list", "-i", "--json");
		String callResult = forceGetChannel().call(command);
		JSONArray vms = (JSONArray)JSONSerializer.toJSON(callResult);
		for (int i = 0; i < vms.size(); i++)
		{
			JSONObject vmInfo = vms.getJSONObject(i);
			if (vmId.equals(vmInfo.getString("ID")) || vmId.equals(vmInfo.getString("Name")))
				return vmInfo;
		}
		return null;
	}

	private long memSizeStringToLong(String memSize)
	{
		// XXX It is expected that memSize ends with "Mb"
		return Long.parseLong(memSize.substring(0, memSize.length() - 2)) * (1 << 20);
	}

	private static class VMResources implements Serializable
	{
		private static final long serialVersionUID = 1L;
		public int cpus;
		public long ram; // in bytes
		private static final long mb = 1 << 20;
		public VMResources(int cpus, long ram)
		{
			this.cpus = cpus;
			this.ram = ram;
		}
		public void append(VMResources newResources)
		{
			cpus += newResources.cpus;
			ram += newResources.ram;
		}
		public static boolean check(VMResources host, VMResources used, VMResources vm)
		{
			if ((vm.cpus + used.cpus) > host.cpus)
			{
				LOGGER.log(Level.SEVERE, "Exceeding CPU limit: vm=%d used=%d host=%d",
						vm.cpus, used.cpus, host.cpus);
				return false;
			}
			if ((vm.ram + used.ram) > host.ram)
			{
				LOGGER.log(Level.SEVERE, "Exceeding RAM limit (Mb): vm=%d used=%d host=%d",
						vm.ram / mb, used.ram / mb, host.ram / mb);
				return false;
			}
			return true;
		}
		public String toLogString()
		{
			return String.format("CPU=%d RAM=%d", cpus, ram);
		}
	}

	private VMResources parseVMResources(JSONObject vmInfo)
	{
		JSONObject vmHw = vmInfo.getJSONObject("Hardware");
		JSONObject vmCpuInfo = vmHw.getJSONObject("cpu");
		int cpus = vmCpuInfo.getInt("cpus");
		JSONObject vmMemInfo = vmHw.getJSONObject("memory");
		long ram = memSizeStringToLong(vmMemInfo.getString("size"));
		JSONObject vmVideoInfo = vmHw.getJSONObject("video");
		ram += memSizeStringToLong(vmVideoInfo.getString("size"));
		ram += 500 * (1 << 20); // +500Mb for each VM for virtualization overhead
		return new VMResources(cpus, ram);
	}

	private static VMResources getHostResources(Channel ch) throws Exception
	{
		return ch.call(new MasterToSlaveCallable<VMResources, Exception>()
			{
				private long getHostPhysicalMemory()
				{
					try
					{
						MBeanServer server = ManagementFactory.getPlatformMBeanServer();
						Object attribute = server.getAttribute(
								new ObjectName("java.lang", "type", "OperatingSystem"),
								"TotalPhysicalMemorySize");
						return (Long)attribute;
					}
					catch (JMException e)
					{
						LOGGER.log(Level.SEVERE, "Failed to get host RAM size: %s", e);
						return Long.MAX_VALUE;
					}
				}

				@Override
				public VMResources call() throws Exception
				{
					int cpus = Runtime.getRuntime().availableProcessors();
					long ram = getHostPhysicalMemory();
					return new VMResources(cpus, ram);
				}
			});
	}

	private boolean checkResourceLimitsForVm(String vmId)
	{
		try
		{
			if (hostResources == null)
			{
				hostResources = getHostResources(forceGetChannel());
				LOGGER.log(Level.SEVERE, "Host '%s' resources: %s", getName(), hostResources.toLogString());
			}

			VMResources vmResources = null;
			VMResources usedResources = new VMResources(0, 1 << 30); // +1Gb for host OS and apps
			RunVmCallable command = new RunVmCallable("list", "-i", "-a", "--json");
			String callResult = forceGetChannel().call(command);
			JSONArray vms = (JSONArray)JSONSerializer.toJSON(callResult);
			for (int i = 0; i < vms.size(); i++)
			{
				JSONObject vmInfo = vms.getJSONObject(i);
				String vmStatus = vmInfo.getString("State");
				if (vmStatus.equals("stopped") || vmStatus.equals("suspended"))
				{
					if (vmInfo.getString("Name").equals(vmId) || vmInfo.getString("ID").equals(vmId))
						vmResources = parseVMResources(vmInfo);
				}
				else if (!vmStatus.equals("invalid"))
				{
					LOGGER.log(Level.FINE , "Accounting VM '%s'", vmInfo.getString("Name"));
					usedResources.append(parseVMResources(vmInfo));
				}
			}
			if (vmResources == null)
				// This means that at this point VM of interest is already in running
				// state, somebody has started it. So there's no meaning to check something.
				return true;
			if (!VMResources.check(hostResources, usedResources, vmResources))
				return false;
			return true;
		}
		catch (Exception ex)
		{
			LOGGER.log(Level.SEVERE, "Error: %s\nFailed to check resource limits", ex);
		}
		return false;
	}

	public Node createSlaveOnVM(ParallelsDesktopVM vm) throws Exception
	{
		String vmId = vm.getVmid();
		LOGGER.log(Level.SEVERE, "Waiting for IP...");
		String ip;
		try
		{
			ip = getVmIPAddress(vmId);
			LOGGER.log(Level.SEVERE, "Got IP address for VM %s: %s", vmId, ip);
			vm.setLauncherIP(ip);
		}
		catch (Exception e)
		{
			if (vm.getLauncherIP() == null)
				throw e;
		}

		String slaveName = vm.getSlaveName();
		LOGGER.log(Level.FINE, "Starting slave '%s'", slaveName);
		Node n = new ParallelsDesktopVMSlave(vm, this);
		LOGGER.log(Level.SEVERE, "Slave %s provisioned.", slaveName);
		return n;
	}

	public ParallelsDesktopVM startVM(ParallelsDesktopVM vm)
	{
		String vmId = vm.getVmid();
		LOGGER.log(Level.SEVERE, "Looking for virtual machine '%s'...", vmId);
		try
		{
			JSONObject vmInfo = getVMInfo(vmId);
			if (vmInfo == null)
			{
				LOGGER.log(Level.SEVERE, "Failed to start virtual machine '%s': no such VM", vmId);
				return null;
			}

			String vmStatus = vmInfo.getString("State");
			ParallelsDesktopVM.VMStates state = ParallelsDesktopVM.parseVMState(vmStatus);
			if (state == null)
			{
				LOGGER.log(Level.SEVERE, "Unexpected VM '%s' state: %s", vmId, vmStatus);
				state = ParallelsDesktopVM.VMStates.Suspended;
			}
			if (vm.getPostBuildBehaviorValue() == ParallelsDesktopVM.PostBuildBehaviors.ReturnPrevState)
				vm.setPrevVMState(state);

			if (state != ParallelsDesktopVM.VMStates.Running)
			{
				if (!checkResourceLimitsForVm(vmId))
				{
					LOGGER.log(Level.SEVERE, "Not enough resources to start VM %s", vmId);
					return null;
				}
				if (maxConfiguredSlaves != -1 && numSlavesRunning + 1 > maxConfiguredSlaves)
				{
					LOGGER.log(Level.WARNING, "Too many slaves currently running, " +
													  "max configured running slave count: %d", maxConfiguredSlaves);
					return null;
				}
				LOGGER.log(Level.SEVERE, "Starting virtual machine '%s'", vmId);

				if (useLinkedClones)
				{
					vm = createLinkedClone(vm);
					vmId = vm.getVmid();
				}
				RunVmCallable command = new RunVmCallable("start", vmId);
				forceGetChannel().call(command);
			}
			if (vm.getPostBuildCommand() != null)
				++numSlavesRunning;
			vm.setProvisioned(true);
			return vm;
		}
		catch (Exception ex)
		{
			LOGGER.log(Level.SEVERE, "Error: %s\nFailed to start VM '%s'", ex, vmId);
		}
		stopVM(vm);
		return null;
	}

	private void stopVM(ParallelsDesktopVM vm)
	{
		try
		{
			if (vm.isLinkedClone())
			{
				LOGGER.log(Level.SEVERE, "Stopping linked clone '%s'", vm.getVmid());
				RunVmCallable command = new RunVmCallable("stop", vm.getVmid(), "--kill");
				String res = forceGetChannel().call(command);
				LOGGER.log(Level.SEVERE, "Result: %s", res);
				
				LOGGER.log(Level.SEVERE, "Deleting linked clone '%s'", vm.getVmid());
				command = new RunVmCallable("delete", vm.getVmid());
				res = forceGetChannel().call(command);
				LOGGER.log(Level.SEVERE, "Result: %s", res);
			}
			else
			{
				String action = vm.getPostBuildCommand();
				if (action == null)
				{
					LOGGER.log(Level.SEVERE, "Keep running VM %s", vm.getVmid());
					return;
				}
				LOGGER.log(Level.SEVERE, "Post build action for '%s': %s", vm.getVmid(), action);
				RunVmCallable command = new RunVmCallable(action, vm.getVmid());
				String res = forceGetChannel().call(command);
				LOGGER.log(Level.SEVERE, "Result: %s", res);
			}
			if (numSlavesRunning > 0)
				--numSlavesRunning;
			vm.setProvisioned(false);
		}
		catch (Exception ex)
		{
			LOGGER.log(Level.SEVERE, "Error: %s", ex);
		}
	}
	
	private ParallelsDesktopVM createLinkedClone(ParallelsDesktopVM vm)
	{
		try
		{
			final ParallelsDesktopVM clone = vm.createLinkedClone();
			
			LOGGER.log(Level.SEVERE, "Creating linked clone for '%s'", clone.getParentVmid());
			RunVmCallable command = new RunVmCallable("clone", clone.getParentVmid(), "--linked", "--name", clone.getVmid());
			String res = forceGetChannel().call(command);
			LOGGER.log(Level.SEVERE, "Result: %s", res);
			
			return clone;
		}
		catch (Exception ex)
		{
			LOGGER.log(Level.SEVERE, "Error: %s", ex);
			return null;
		}
	}

	public void postBuildAction(ParallelsDesktopVM vm)
	{
		stopVM(vm);
	}

	public boolean isReadyToRestart()
	{
		if (isOffline())
			return true;
		return numSlavesRunning == 0;
	}

	private static final class PrlCtlFailedException extends Exception
	{
		private static String formatMessage(int rc, String output)
		{
			String msg = String.format("prlctl execution failed with code %d", rc);
			if (!output.isEmpty())
				msg += String.format(" , output:\n%s", output);
			return msg;
		}

		private PrlCtlFailedException(int rc, String output)
		{
			super(formatMessage(rc, output));
		}
	}
	
	public Channel forceGetChannel() throws InterruptedException, ExecutionException
	{
		Channel channel = getChannel();
		if (channel == null)
		{
			connect(true).get();
			channel = getChannel();
		}
		return channel;
	}

	private static final class RunVmCallable extends MasterToSlaveCallable<String, Exception>
	{
		private static final String cmd = "/usr/local/bin/prlctl";
		private final String[] params;
		
		public RunVmCallable(String... params)
		{
			this.params = params;
		}

		@Override
		public String call() throws IOException, PrlCtlFailedException
		{
			List<String> cmds = new ArrayList<String>();
			cmds.add(cmd);
			cmds.addAll(Arrays.asList(this.params));
			
			LOGGER.log(Level.SEVERE, "Running command:");
			for (String s: cmds)
				LOGGER.log(Level.SEVERE, " [%s]", s);
			ProcessBuilder pb = new ProcessBuilder(cmds);
			pb.redirectErrorStream(true);
			Process pr = pb.start();
			BufferedReader in = new BufferedReader(new InputStreamReader(pr.getInputStream()));
			String line;
			String result = "";
			while ((line = in.readLine()) != null) 
			{
				result += line;
			}
			int rc = 0;
			try
			{
				rc = pr.waitFor();
			}
			catch (InterruptedException ex)
			{
				LOGGER.log(Level.SEVERE, "Error: %s", ex.toString());
			}
			if (rc != 0)
				throw new PrlCtlFailedException(rc, result);
			return result;
		}
	}
	
	@Override
	public boolean hasPermission(Permission permission)
	{
		if (permission == CONFIGURE)
			return false;
		return super.hasPermission(permission);
	}

	@Override
	public void setTemporarilyOffline(boolean temporarilyOffline, OfflineCause cause)
	{
		try
		{
			Jenkins jenkins = Jenkins.getInstance();
			for (ParallelsDesktopVM vm : this.getNode().getOwner().getVms())
			{
				Node slaveNode = jenkins.getNode(vm.getSlaveName());
				if (slaveNode == null)
				{
					continue;
				}
				Computer slaveComputer = slaveNode.toComputer();
				slaveComputer.setTemporarilyOffline(temporarilyOffline, cause);
			}
		}
		catch (NullPointerException ignore)
		{
			//noop
		}
		finally
		{
			super.setTemporarilyOffline(temporarilyOffline, cause);
		}
	}
}
