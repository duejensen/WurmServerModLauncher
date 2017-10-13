package org.gotti.wurmunlimited.mods.serverpacks;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.gotti.wurmunlimited.modcomm.Channel;
import org.gotti.wurmunlimited.modcomm.IChannelListener;
import org.gotti.wurmunlimited.modcomm.ModComm;
import org.gotti.wurmunlimited.modcomm.PacketReader;
import org.gotti.wurmunlimited.modcomm.PacketWriter;
import org.gotti.wurmunlimited.modloader.interfaces.Configurable;
import org.gotti.wurmunlimited.modloader.interfaces.Initable;
import org.gotti.wurmunlimited.modloader.interfaces.ModEntry;
import org.gotti.wurmunlimited.modloader.interfaces.ModListener;
import org.gotti.wurmunlimited.modloader.interfaces.ServerStartedListener;
import org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod;
import org.gotti.wurmunlimited.mods.httpserver.ModHttpServerImpl;
import org.gotti.wurmunlimited.mods.httpserver.api.ModHttpServer;

import com.wurmonline.server.players.Player;

public class ServerPackMod implements WurmServerMod, ModListener, Initable, Configurable, ServerStartedListener {

	private static final byte CMD_REFRESH = 0x01;

	private Map<String, PackInfo> packs = new HashMap<>();

	private Logger logger = Logger.getLogger(ServerPackMod.class.getName());

	private Channel channel;

	private String prefix;

	@Override
	public void init() {
		channel = ModComm.registerChannel("ago.serverpacks", new IChannelListener() {
			@Override
			public void onPlayerConnected(Player player) {
				if (!ModHttpServer.getInstance().isRunning()) {
					logger.log(Level.WARNING, "HTTP server did not start properly. No server packs will be delivered.");
					return;
				}
				try {
					URI uri = ModHttpServer.getInstance().getUri().resolve(prefix);
					try (PacketWriter writer = new PacketWriter()) {
						writer.writeInt(packs.size());
						for (Map.Entry<String, PackInfo> entry : packs.entrySet()) {
							final String packId = entry.getKey();
							final PackInfo info = entry.getValue();
							final String query = info.prepend ? "?prepend" : "";
							final URI packUri = uri.resolve(packId);
							writer.writeUTF(packId);
							writer.writeUTF(packUri.toString() + query);
						}
						channel.sendMessage(player, writer.getBytes());
					}
				} catch (IOException | URISyntaxException e) {
					logger.log(Level.WARNING, e.getMessage(), e);
				}
			}

			@Override
			public void handleMessage(Player player, ByteBuffer message) {
				try (PacketReader reader = new PacketReader(message)) {
					byte cmd = reader.readByte();
					switch (cmd) {
					case CMD_REFRESH:
						sendModelRefresh(player);
						break;
					default:
						logger.log(Level.WARNING, String.format("Unknown channel command 0x%02x", 128 + cmd));
						break;
					}
				} catch (IOException e) {
					logger.log(Level.WARNING, e.getMessage(), e);
				}
			}
		});
	}

	private void sendModelRefresh(Player player) {
		try {
			player.createVisionArea();
		} catch (Exception e) {
			logger.log(Level.WARNING, e.getMessage(), e);
		}
	}

	@Override
	public void configure(Properties properties) {
			final int serverPort = Integer.parseInt(properties.getProperty("serverPort", Integer.toString(0)));
			final int publicServerPort = Integer.parseInt(properties.getProperty("publicServerPort", Integer.toString(0)));
			final String publicServerAddress = properties.getProperty("publicServerAddress");
			final String internalServerAddress = properties.getProperty("internalServerAddress");

			if (serverPort != 0 || publicServerPort != 0 || internalServerAddress != null || publicServerAddress != null) {
				logger.warning("Overriding httpserver configuration");
				logger.info("serverPort: " + serverPort);
				logger.info("publicServerAddress: " + publicServerAddress);
				logger.info("publicServerPort: " + publicServerPort);
				logger.info("internalServerAddress: " + internalServerAddress);

				ModHttpServerImpl.getInstance().configure(internalServerAddress, serverPort, publicServerAddress, publicServerPort);
			}
	}

	@Override
	public void modInitialized(ModEntry<?> entry) {
		if (entry == null || entry.getProperties() == null)
			return;

		String serverPacks = entry.getProperties().getProperty("serverPacks");
		if (serverPacks == null) {
			return;
		}

		String[] packs = serverPacks.split(",");

		for (String pack : packs) {
			try {
				pack = pack.trim();
				final boolean prepend = pack.startsWith("!");
				final String fileName = prepend ? pack.substring(1) : pack;
				
				Path packPath = Paths.get("mods").resolve(entry.getName()).resolve(Paths.get(fileName));
				if (Files.isRegularFile(packPath)) {
					addPack(packPath, prepend);
				} else {
					logger.log(Level.WARNING, "Missing serverPack " + packPath);
				}
			} catch (IOException | NoSuchAlgorithmException e) {
				logger.log(Level.WARNING, "Error reading server pack", e);
			}
		}
	}

	private String getSha1Sum(Path packPath) throws IOException, NoSuchAlgorithmException {
		MessageDigest messageDigest = MessageDigest.getInstance("SHA-1");
		messageDigest.reset();

		try (InputStream is = Files.newInputStream(packPath)) {
			int n = 0;
			byte[] buffer = new byte[8192];
			while (n != -1) {
				n = is.read(buffer);
				if (n > 0) {
					messageDigest.update(buffer, 0, n);
				}
			}
		}
		byte[] digest = messageDigest.digest();
		return javax.xml.bind.DatatypeConverter.printHexBinary(digest);
	}

	private void addPack(Path packPath, boolean prepend) throws NoSuchAlgorithmException, IOException {
		String sha1Sum = getSha1Sum(packPath);
		packs.put(sha1Sum, new PackInfo(packPath, prepend));
		logger.info("Added pack " + sha1Sum + " for pack " + packPath);
	}
	
	private InputStream servePack(String packid) {
		try {
			PackInfo info = packs.get(packid);
			if (info != null && info.path != null) {
				return Files.newInputStream(info.path);
			}
		} catch (IOException e) {
			logger.log(Level.WARNING, e.getMessage(), e);
		}
		return null;
	}

	@Override
	public void onServerStarted() {
		this.prefix = ModHttpServer.getInstance().serve(this, Pattern.compile("^/(?<path>[^/]*)$"), this::servePack);
		if (prefix == null) {
			throw new RuntimeException("Failed to register server pack http handler");
		}
	}
}
