/*******************************************************************************
 * Copyright (c) 2016 Sebastian Stenzel and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the accompanying LICENSE.txt.
 *
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 *******************************************************************************/
package org.cryptomator.cryptofs;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.cryptomator.cryptofs.CryptoPathMapper.Directory;
import org.cryptomator.cryptolib.DaggerCryptoLibComponent;
import org.cryptomator.cryptolib.api.CryptorProvider;
import org.cryptomator.cryptolib.api.FileNameCryptor;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.google.common.collect.Iterators;

public class CryptoDirectoryStreamTest {

	private static final Consumer<CryptoDirectoryStream> DO_NOTHING_ON_CLOSE = ignored -> {
	};
	private static final Filter<? super Path> ACCEPT_ALL = ignored -> true;

	private static CryptorProvider cryptorProvider;

	@BeforeClass
	public static void setupClass() {
		cryptorProvider = DaggerCryptoLibComponent.builder() //
				.secureRandomModule(new TestSecureRandomModule()) //
				.build() //
				.version1();
	}

	private FileNameCryptor filenameCryptor;
	private Path ciphertextDirPath;
	private DirectoryStream<Path> dirStream;
	private LongFileNameProvider longFileNameProvider;

	@Before
	@SuppressWarnings("unchecked")
	public void setup() throws IOException {
		filenameCryptor = cryptorProvider.createNew().fileNameCryptor();

		ciphertextDirPath = Mockito.mock(Path.class);
		FileSystem fs = Mockito.mock(FileSystem.class);
		Mockito.when(ciphertextDirPath.getFileSystem()).thenReturn(fs);
		FileSystemProvider provider = Mockito.mock(FileSystemProvider.class);
		Mockito.when(fs.provider()).thenReturn(provider);
		dirStream = Mockito.mock(DirectoryStream.class);
		Mockito.when(provider.newDirectoryStream(Mockito.same(ciphertextDirPath), Mockito.any())).thenReturn(dirStream);
		longFileNameProvider = Mockito.mock(LongFileNameProvider.class);
		Mockito.when(longFileNameProvider.inflate(Mockito.anyString())).thenAnswer(new Answer<String>() {

			@Override
			public String answer(InvocationOnMock invocation) throws Throwable {
				String shortName = invocation.getArgument(0);
				if (shortName.contains("invalid")) {
					throw new IOException("invalid shortened name");
				} else {
					return StringUtils.removeEnd(shortName, ".lng");
				}
			}

		});
	}

	@Test
	public void testDirListing() throws IOException {
		Path cleartextPath = Paths.get("/foo/bar");

		List<String> ciphertextFileNames = new ArrayList<>();
		ciphertextFileNames.add(filenameCryptor.encryptFilename("one", "foo".getBytes()));
		ciphertextFileNames.add(filenameCryptor.encryptFilename("two", "foo".getBytes()) + "_conflict");
		ciphertextFileNames.add("0" + filenameCryptor.encryptFilename("three", "foo".getBytes()));
		ciphertextFileNames.add("invalidLongName.lng");
		ciphertextFileNames.add(filenameCryptor.encryptFilename("four", "foo".getBytes()) + ".lng");
		ciphertextFileNames.add(filenameCryptor.encryptFilename("invalid", "bar".getBytes()));
		ciphertextFileNames.add("alsoInvalid");
		Mockito.when(dirStream.iterator()).thenReturn(Iterators.transform(ciphertextFileNames.iterator(), cleartextPath::resolve));

		try (CryptoDirectoryStream stream = new CryptoDirectoryStream(new Directory("foo", ciphertextDirPath), cleartextPath, filenameCryptor, longFileNameProvider, ACCEPT_ALL, DO_NOTHING_ON_CLOSE)) {
			Iterator<Path> iter = stream.iterator();
			Assert.assertTrue(iter.hasNext());
			Assert.assertEquals(cleartextPath.resolve("one"), iter.next());
			Assert.assertTrue(iter.hasNext());
			Assert.assertEquals(cleartextPath.resolve("two"), iter.next());
			Assert.assertTrue(iter.hasNext());
			Assert.assertEquals(cleartextPath.resolve("three"), iter.next());
			Assert.assertTrue(iter.hasNext());
			Assert.assertEquals(cleartextPath.resolve("four"), iter.next());
			Assert.assertFalse(iter.hasNext());
			Mockito.verify(dirStream, Mockito.never()).close();
		}
		Mockito.verify(dirStream).close();
	}

	@Test(expected = NoSuchElementException.class)
	public void testDirListingForEmptyDir() throws IOException {
		Path cleartextPath = Paths.get("/foo/bar");

		Mockito.when(dirStream.iterator()).thenReturn(Collections.emptyIterator());

		try (CryptoDirectoryStream stream = new CryptoDirectoryStream(new Directory("foo", ciphertextDirPath), cleartextPath, filenameCryptor, longFileNameProvider, ACCEPT_ALL, DO_NOTHING_ON_CLOSE)) {
			Iterator<Path> iter = stream.iterator();
			Assert.assertFalse(iter.hasNext());
			iter.next();
		}
	}

}
