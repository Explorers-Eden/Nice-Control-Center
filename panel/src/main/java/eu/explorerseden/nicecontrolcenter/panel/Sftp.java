package eu.explorerseden.nicecontrolcenter.panel;

import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.Handle;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.AccessDeniedException;
import java.nio.file.CopyOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.PublicKey;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SFTP into the server folder with panel accounts: password, or an SSH key added under Account.
 * Needs the files.sftp permission; without files.write the folder is read-only. The world folder is
 * read-only while the server runs, like in the file explorer. Changes go to the audit log.
 */
final class Sftp {
	private static final AttributeRepository.AttributeKey<Auth.Session> USER = new AttributeRepository.AttributeKey<>();
	private static final Set<OpenOption> WRITES = Set.of(StandardOpenOption.WRITE, StandardOpenOption.APPEND, StandardOpenOption.CREATE,
			StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING);

	private final ServerFiles files;
	private final Auth auth;
	private final Audit audit;
	private SshServer sshd;

	Sftp(ServerFiles files, Auth auth, Audit audit) {
		this.files = files;
		this.auth = auth;
		this.audit = audit;
	}

	void start(int port, Path hostKey) throws IOException {
		sshd = SshServer.setUpDefaultServer();
		sshd.setPort(port);
		SimpleGeneratorHostKeyProvider keys = new SimpleGeneratorHostKeyProvider(hostKey);
		keys.setAlgorithm(KeyUtils.EC_ALGORITHM);
		sshd.setKeyPairProvider(keys);
		sshd.setPasswordAuthenticator((name, password, session) -> accept(session, auth.verify(name, password, ip(session)), "password"));
		sshd.setPublickeyAuthenticator((name, key, session) -> accept(session, keyLogin(name, key), "key"));
		sshd.setKeyboardInteractiveAuthenticator(null);
		sshd.setUserAuthFactories(List.of(org.apache.sshd.server.auth.password.UserAuthPasswordFactory.INSTANCE,
				org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory.INSTANCE));
		sshd.setFileSystemFactory(new VirtualFileSystemFactory(files.root()));
		SftpSubsystemFactory sftp = new SftpSubsystemFactory.Builder().build();
		sftp.addSftpEventListener(new Guard());
		sshd.setSubsystemFactories(List.of(sftp));
		sshd.start();
		System.out.println("SFTP listening on port " + port);
	}

	void stop() {
		try {
			if (sshd != null) sshd.stop(true);
		} catch (IOException ignored) {
			// Shutting down anyway.
		}
	}

	private boolean accept(ServerSession session, Auth.Session user, String how) {
		if (user == null) return false;
		if (!user.can(Permissions.FILES_SFTP)) {
			audit.log(user.name(), "sftp.refused", "no SFTP permission", ip(session));
			return false;
		}
		// The same key/password check can run more than once per connection; log the login only once.
		if (session.getAttribute(USER) == null) audit.log(user.name(), "sftp.login", "with " + how + (user.can(Permissions.FILES_WRITE) ? "" : ", read-only"), ip(session));
		session.setAttribute(USER, user);
		return true;
	}

	private Auth.Session keyLogin(String name, PublicKey offered) {
		Auth.Session user = auth.user(name);
		if (user == null || auth.accounts() == null) return null;
		try {
			for (Accounts.SshKey k : auth.accounts().keys(user.userId())) {
				PublicKey stored = AuthorizedKeyEntry.parseAuthorizedKeyEntry(k.publicKey()).resolvePublicKey(null, PublicKeyEntryResolver.IGNORING);
				if (stored != null && KeyUtils.compareKeys(stored, offered)) {
					auth.accounts().touchKey(k.id());
					return user;
				}
			}
		} catch (SQLException | IOException | java.security.GeneralSecurityException | RuntimeException e) {
			return null;
		}
		return null;
	}

	private static String ip(ServerSession session) {
		SocketAddress address = session.getClientAddress();
		return address instanceof InetSocketAddress inet ? inet.getAddress().getHostAddress() : String.valueOf(address);
	}

	/** Refuses changes the user may not make, and logs the ones that happen. */
	private final class Guard implements SftpEventListener {
		private Auth.Session user(ServerSession session) {
			return session.getAttribute(USER);
		}

		private void checkWrite(ServerSession session, Path path) throws IOException {
			Auth.Session user = user(session);
			if (user == null || !user.can(Permissions.FILES_WRITE)) throw new AccessDeniedException(path.toString(), null, "read-only account");
			try {
				files.checkWritable(local(path));
			} catch (SecurityException e) {
				throw new AccessDeniedException(path.toString(), null, e.getMessage());
			}
		}

		/** The SFTP path ("/world/level.dat") as a path in the server folder. */
		private Path local(Path path) throws IOException {
			return files.resolve(path.toString());
		}

		private String rel(Path path) {
			try {
				return files.rel(local(path));
			} catch (IOException | RuntimeException e) {
				return path.toString();
			}
		}

		@Override
		public void opening(ServerSession session, String remoteHandle, Handle localHandle) throws IOException {
			if (localHandle instanceof FileHandle file && file.getOpenOptions().stream().anyMatch(WRITES::contains)) {
				checkWrite(session, localHandle.getFile());
			}
		}

		@Override
		public void closed(ServerSession session, String remoteHandle, Handle localHandle, Throwable thrown) {
			if (thrown == null && localHandle instanceof FileHandle file && file.getOpenOptions().stream().anyMatch(WRITES::contains)) {
				Auth.Session user = user(session);
				audit.log(user == null ? null : user.name(), "sftp.upload", rel(localHandle.getFile()), ip(session));
			}
		}

		@Override
		public void creating(ServerSession session, Path path, Map<String, ?> attrs) throws IOException {
			checkWrite(session, path);
		}

		@Override
		public void moving(ServerSession session, Path srcPath, Path dstPath, Collection<CopyOption> opts) throws IOException {
			checkWrite(session, srcPath);
			checkWrite(session, dstPath);
		}

		@Override
		public void moved(ServerSession session, Path srcPath, Path dstPath, Collection<CopyOption> opts, Throwable thrown) {
			if (thrown == null) {
				Auth.Session user = user(session);
				audit.log(user == null ? null : user.name(), "sftp.move", rel(srcPath) + " → " + rel(dstPath), ip(session));
			}
		}

		@Override
		public void removing(ServerSession session, Path path, boolean isDirectory) throws IOException {
			checkWrite(session, path);
		}

		@Override
		public void removed(ServerSession session, Path path, boolean isDirectory, Throwable thrown) {
			if (thrown == null) {
				Auth.Session user = user(session);
				audit.log(user == null ? null : user.name(), "sftp.delete", rel(path) + (isDirectory ? "/" : ""), ip(session));
			}
		}

		@Override
		public void linking(ServerSession session, Path source, Path target, boolean symLink) throws IOException {
			// Links could point outside the server folder.
			throw new AccessDeniedException(source.toString(), null, "links aren't allowed");
		}

		@Override
		public void modifyingAttributes(ServerSession session, Path path, Map<String, ?> attrs) throws IOException {
			checkWrite(session, path);
		}
	}
}
