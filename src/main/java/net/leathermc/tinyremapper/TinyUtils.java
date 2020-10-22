/*
 * Copyright (C) 2016, 2018 Player, asie
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.leathermc.tinyremapper;

import net.leathermc.tinyremapper.IMappingProvider.MappingAcceptor;
import net.leathermc.tinyremapper.IMappingProvider.Member;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Remapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.zip.GZIPInputStream;

public final class TinyUtils {
	private static final class MemberMapping {
		public MemberMapping(Member member, String newName) {
			this.member = member;
			this.newName = newName;
		}

		public Member member;
		public String newName;
	}

	private static final class MethodArgMapping {
		public MethodArgMapping(Member method, int lvIndex, String newName) {
			this.method = method;
			this.lvIndex = lvIndex;
			this.newName = newName;
		}

		public Member method;
		public int lvIndex;
		public String newName;
	}

	private static final class MethodVarMapping {
		public MethodVarMapping(Member method, int lvIndex, int startOpIdx, int asmIndex, String newName) {
			this.method = method;
			this.lvIndex = lvIndex;
			this.startOpIdx = startOpIdx;
			this.asmIndex = asmIndex;
			this.newName = newName;
		}

		public Member method;
		public int lvIndex, startOpIdx, asmIndex;
		public String newName;
	}

	private static class SimpleClassMapper extends Remapper {
		final Map<String, String> classMap;

		public SimpleClassMapper(Map<String, String> map) {
			this.classMap = map;
		}

		@Override
		public String map(String typeName) {
			return classMap.getOrDefault(typeName, typeName);
		}
	}

	private TinyUtils() {

	}

	public static IMappingProvider createTinyMappingProvider(final Path mappings, String fromM, String toM) {
		return out -> {
			try (BufferedReader reader = getMappingReader(mappings.toFile())) {
				readInternal(reader, fromM, toM, out);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}

			//System.out.printf("%s: %d classes, %d methods, %d fields%n", mappings.getFileName().toString(), classMap.size(), methodMap.size(), fieldMap.size());
		};
	}

	private static BufferedReader getMappingReader(File file) throws IOException {
		InputStream is = new FileInputStream(file);

		if (file.getName().endsWith(".gz")) {
			is = new GZIPInputStream(is);
		}

		return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
	}

	public static IMappingProvider createTinyMappingProvider(final BufferedReader reader, String fromM, String toM) {
		return out -> {
			try {
				readInternal(reader, fromM, toM, out);
			} catch (Exception e) {
				e.printStackTrace();
			}

			//System.out.printf("%d classes, %d methods, %d fields%n", classMap.size(), methodMap.size(), fieldMap.size());
		};
	}

	private static void readInternal(BufferedReader reader, String fromM, String toM, MappingAcceptor out) {
		List<MemberMapping> methodMappings = new ArrayList<>();
		List<MethodArgMapping> methodArgMappings = new ArrayList<>();
		List<MethodVarMapping> methodVarMappings = new ArrayList<>();
		List<MemberMapping> fieldMappings = new ArrayList<>();
		Set<Member> members = Collections.newSetFromMap(new IdentityHashMap<>()); // for remapping members exactly once in postprocessing

		MappingAcceptor tmp = new MappingAcceptor() {
			@Override
			public void acceptClass(String srcName, String dstName) {
				out.acceptClass(srcName, dstName);
			}

			@Override
			public void acceptMethod(Member method, String dstName) {
				methodMappings.add(new MemberMapping(method, dstName));
				members.add(method);
			}

			@Override
			public void acceptMethodArg(Member method, int lvIndex, String dstName) {
				methodArgMappings.add(new MethodArgMapping(method, lvIndex, dstName));
				members.add(method);
			}

			@Override
			public void acceptMethodVar(Member method, int lvIndex, int startOpIdx, int asmIndex, String dstName) {
				methodVarMappings.add(new MethodVarMapping(method, lvIndex, startOpIdx, asmIndex, dstName));
				members.add(method);
			}

			@Override
			public void acceptField(Member field, String dstName) {
				fieldMappings.add(new MemberMapping(field, dstName));
				members.add(field);
			}
		};

		try {
			TinyUtils.read(reader, fromM, toM,
					tmp,
					(remapClasses, classMapper) -> {
						for (Member m : members) {
							if (remapClasses) m.owner = classMapper.map(m.owner);
							m.desc = classMapper.mapDesc(m.desc);
						}
					});
		} catch (Exception e) {
			e.printStackTrace();
		}

		for (MemberMapping m : methodMappings) {
			out.acceptMethod(m.member, m.newName);
		}

		for (MethodArgMapping m : methodArgMappings) {
			out.acceptMethodArg(m.method, m.lvIndex, m.newName);
		}

		for (MethodVarMapping m : methodVarMappings) {
			out.acceptMethodVar(m.method, m.lvIndex, m.startOpIdx, m.asmIndex, m.newName);
		}

		for (MemberMapping m : fieldMappings) {
			out.acceptField(m.member, m.newName);
		}
	}

	public static void read(BufferedReader reader, String from, String to,
	                        MappingAcceptor out,
	                        BiConsumer<Boolean, SimpleClassMapper> postProcessor) throws Exception {
		String headerLine = reader.readLine();

		if (headerLine == null) {
			throw new EOFException();
		} else if (headerLine.startsWith("tot")) {
			readTaterV1(reader, from, to, headerLine, out, postProcessor);
		} else if (headerLine.startsWith("v1")) { // TinyV1 support
			readV1(reader, from, to, headerLine, out, postProcessor);
		} else if (headerLine.startsWith("tiny\tv2\t")) { // TinyV2 support
			readV2(reader, from, to, headerLine, out, postProcessor);
		} else {
			throw new IOException("Invalid mapping version!");
		}
	}

	private static void readTaterV1(BufferedReader reader, String from, String to,
			String headerLine,
			MappingAcceptor out,
			BiConsumer<Boolean, SimpleClassMapper> postProcessor) throws Exception {
		List<String> headerList = Arrays.asList(headerLine.split("\t"));
		int fromIndex = headerList.indexOf(from) - 1;
		int toIndex = headerList.indexOf(to) - 1;

		if (fromIndex < 0) throw new IOException("Could not find mapping '" + from + "'!");
		if (toIndex < 0) throw new IOException("Could not find mapping '" + to + "'!");

		Map<String, String> obfFrom = fromIndex != 0 ? new HashMap<>() : null;

		String line;
		String lastClass = "";
		Member lastMethod = null;
		// O(n^2)
		// todo: optimize
		while ((line = reader.readLine()) != null) {
			int indent = 0;
			String[] splitLine = line.split("\t");
			if (splitLine.length < 2) continue;

			for (String c : splitLine) {
				if (c.equals("")) {
					indent++;
				}
			}

			String type = splitLine[indent];

			if (type.equals("c")) {
				String fromName = splitLine[1 + fromIndex];
				String toName = splitLine[1 + toIndex];
				System.out.println(fromName + " " + toName);
				out.acceptClass(fromName, toName);
				lastClass = fromName;
			} else if (indent > 0) {
				String fromName = type.equals("p") ? null : splitLine[1 + fromIndex + indent];
				String toName = splitLine[(type.equals("p") ? 0 : 1) + toIndex + indent];
				String desc = splitLine[2 + indent + (type.equals("p") ? 0 : 1)];
				if (fromName == null) {
					fromName = placeholder(lastMethod.args.get(Integer.parseInt(desc)), Integer.parseInt(desc));
				}
				System.out.println(fromName + " " + toName + " " + desc);
				Member member = type.equals("p") ? null : new Member(lastClass, fromName, desc);

				switch (type) {
					case "m": {
						out.acceptMethod(member, toName);
						lastMethod = member;
						break;
					}
					case "f": {
						out.acceptField(member, toName);
						break;
					}
					case "p": {
						if (indent != 2) break;
						out.acceptMethodArg(lastMethod, Integer.parseInt(desc), toName);
						break;
					}
					// todo: var mapping
					/*case "v": {
						if (indent != 2) break;
						out.acceptMethodVar(lastMethod, varAmount);
						varAmount++;
						break;
					}*/
					default: {
						throw new RuntimeException("Invalid type: " + type);
					}
				}
			} else {
				// fixme: why does it say NaN :concern:
				throw new RuntimeException("[Line " + Float.NaN + "] Unexpected string: \"" + Arrays.toString(splitLine) + "\"\nType: " + type);
			}
		}

		if (obfFrom != null) {
			postProcessor.accept(true, new SimpleClassMapper(obfFrom));
		}
	}

	private static void readV1(BufferedReader reader, String from, String to,
			String headerLine,
			MappingAcceptor out,
			BiConsumer<Boolean, SimpleClassMapper> postProcessor)
					throws IOException {
		List<String> headerList = Arrays.asList(headerLine.split("\t"));
		int fromIndex = headerList.indexOf(from) - 1;
		int toIndex = headerList.indexOf(to) - 1;

		if (fromIndex < 0) throw new IOException("Could not find mapping '" + from + "'!");
		if (toIndex < 0) throw new IOException("Could not find mapping '" + to + "'!");

		Map<String, String> obfFrom = fromIndex != 0 ? new HashMap<>() : null;

		String line;
		while ((line = reader.readLine()) != null) {
			String[] splitLine = line.split("\t");
			if (splitLine.length < 2) continue;

			String type = splitLine[0];

			if ("CLASS".equals(type)) {
				out.acceptClass(splitLine[1 + fromIndex], splitLine[1 + toIndex]);
				if (obfFrom != null) obfFrom.put(splitLine[1], splitLine[1 + fromIndex]);
			} else {
				boolean isMethod;

				if ("FIELD".equals(type)) {
					isMethod = false;
				} else if ("METHOD".equals(type)) {
					isMethod = true;
				} else {
					continue;
				}

				String owner = splitLine[1];
				String name = splitLine[3 + fromIndex];
				String desc = splitLine[2];
				String nameTo = splitLine[3 + toIndex];
				Member member = new Member(owner, name, desc);

				if (isMethod) {
					out.acceptMethod(member, nameTo);
				} else {
					out.acceptField(member, nameTo);
				}
			}
		}

		if (obfFrom != null) {
			postProcessor.accept(true, new SimpleClassMapper(obfFrom));
		}
	}

	private static void readV2(BufferedReader reader, String from, String to,
			String headerLine,
			MappingAcceptor out,
			BiConsumer<Boolean, SimpleClassMapper> postProcessor)
					throws IOException {
		String[] parts;

		if (!headerLine.startsWith("tiny\t2\t")
				|| (parts = splitAtTab(headerLine, 0, 5)).length < 5) { //min. tiny + major version + minor version + 2 name spaces
			throw new IOException("invalid/unsupported tiny file (incorrect header)");
		}

		List<String> namespaces = Arrays.asList(parts).subList(3, parts.length);
		int nsA = namespaces.indexOf(from);
		int nsB = namespaces.indexOf(to);
		Map<String, String> obfFrom = nsA != 0 ? new HashMap<>() : null;
		int partCountHint = 2 + namespaces.size(); // suitable for members, which should be the majority
		int lineNumber = 1;

		boolean inHeader = true;
		boolean inClass = false;
		boolean inMethod = false;

		boolean escapedNames = false;

		String className = null;
		Member member = null;
		int varLvIndex = 0;
		int varStartOpIdx = 0;
		int varLvtIndex = 0;
		String line;

		while ((line = reader.readLine()) != null) {
			lineNumber++;
			if (line.isEmpty()) continue;

			int indent = 0;

			while (indent < line.length() && line.charAt(indent) == '\t') {
				indent++;
			}

			parts = splitAtTab(line, indent, partCountHint);
			String section = parts[0];

			if (indent == 0) {
				inHeader = inClass = inMethod = false;

				if (section.equals("c")) { // class: c <names>...
					if (parts.length != namespaces.size() + 1) throw new IOException("invalid class decl in line "+lineNumber);

					className = unescapeOpt(parts[1 + nsA], escapedNames);
					String mappedName = unescapeOpt(parts[1 + nsB], escapedNames);

					if (!mappedName.isEmpty()) {
						out.acceptClass(className, mappedName);
					}

					if (obfFrom != null) obfFrom.put(unescapeOpt(parts[1], escapedNames), className);

					inClass = true;
				}
			} else if (indent == 1) {
				inMethod = false;

				if (inHeader) { // header k/v
					if (section.equals("escaped-names")) {
						escapedNames = true;
					}
				} else if (inClass && (section.equals("m") || section.equals("f"))) { // method/field: m/f <descA> <names>...
					boolean isMethod = section.equals("m");
					if (parts.length != namespaces.size() + 2) throw new IOException("invalid "+(isMethod ? "method" : "field")+" decl in line "+lineNumber);

					String memberDesc = unescapeOpt(parts[1], escapedNames);
					String memberName = unescapeOpt(parts[2 + nsA], escapedNames);
					String mappedName = unescapeOpt(parts[2 + nsB], escapedNames);
					member = new Member(className, memberName, memberDesc);
					inMethod = isMethod;

					if (!mappedName.isEmpty()) {
						if (isMethod) {
							out.acceptMethod(member, mappedName);
						} else {
							out.acceptField(member, mappedName);
						}
					}
				}
			} else if (indent == 2) {
				if (inMethod && section.equals("p")) { // method parameter: p <lv-index> <names>...
					if (parts.length != namespaces.size() + 2) throw new IOException("invalid method parameter decl in line "+lineNumber);

					varLvIndex = Integer.parseInt(parts[1]);
					String mappedName = unescapeOpt(parts[2 + nsB], escapedNames);
					if (!mappedName.isEmpty()) out.acceptMethodArg(member, varLvIndex, mappedName);
				} else if (inMethod && section.equals("v")) { // method variable: v <lv-index> <lv-start-offset> <optional-lvt-index> <names>...
					if (parts.length != namespaces.size() + 4) throw new IOException("invalid method variable decl in line "+lineNumber);

					varLvIndex = Integer.parseInt(parts[1]);
					varStartOpIdx = Integer.parseInt(parts[2]);
					varLvtIndex = Integer.parseInt(parts[3]);
					String mappedName = unescapeOpt(parts[4 + nsB], escapedNames);
					if (!mappedName.isEmpty()) out.acceptMethodVar(member, varLvIndex, varStartOpIdx, varLvtIndex, mappedName);
				}
			}
		}

		if (obfFrom != null) {
			postProcessor.accept(false, new SimpleClassMapper(obfFrom));
		}
	}

	private static String[] splitAtTab(String s, int offset, int partCountHint) {
		String[] ret = new String[Math.max(1, partCountHint)];
		int partCount = 0;
		int pos;

		while ((pos = s.indexOf('\t', offset)) >= 0) {
			if (partCount == ret.length) ret = Arrays.copyOf(ret, ret.length * 2);
			ret[partCount++] = s.substring(offset, pos);
			offset = pos + 1;
		}

		if (partCount == ret.length) ret = Arrays.copyOf(ret, ret.length + 1);
		ret[partCount++] = s.substring(offset);

		return partCount == ret.length ? ret : Arrays.copyOf(ret, partCount);
	}

	private static String unescapeOpt(String str, boolean escapedNames) {
		return escapedNames ? unescape(str) : str;
	}

	private static String unescape(String str) {
		int pos = str.indexOf('\\');
		if (pos < 0) return str;

		StringBuilder ret = new StringBuilder(str.length() - 1);
		int start = 0;

		do {
			ret.append(str, start, pos);
			pos++;
			int type;

			if (pos >= str.length()) {
				throw new RuntimeException("incomplete escape sequence at the end");
			} else if ((type = escaped.indexOf(str.charAt(pos))) < 0) {
				throw new RuntimeException("invalid escape character: \\"+str.charAt(pos));
			} else {
				ret.append(toEscape.charAt(type));
			}

			start = pos + 1;
		} while ((pos = str.indexOf('\\', start)) >= 0);

		ret.append(str, start, str.length());

		return ret.toString();
	}

	private static String placeholder(Type type, int index) {
		List<String> a = Arrays.asList(type.getClassName().split("\\."));
		String s = a.get(a.size() - 1);
		return s.toLowerCase().charAt(0) + s.substring(1) + index;
	}

	private static final String toEscape = "\\\n\r\0\t";
	private static final String escaped = "\\nr0t";
}
