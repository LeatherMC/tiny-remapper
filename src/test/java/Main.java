import net.leathermc.tinyremapper.NonClassCopyMode;
import net.leathermc.tinyremapper.OutputConsumerPath;
import net.leathermc.tinyremapper.TinyRemapper;
import net.leathermc.tinyremapper.TinyUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Set;

public class Main {
	public static void main(String[] args) {
		boolean reverse = false;
		boolean ignoreFieldDesc = false;
		boolean propagatePrivate = false;
		boolean removeFrames = false;
		Set<String> forcePropagation = Collections.emptySet();
		File forcePropagationFile = null;
		boolean ignoreConflicts = false;
		boolean checkPackageAccess = false;
		boolean fixPackageAccess = false;
		boolean resolveMissing = false;
		boolean rebuildSourceFilenames = false;
		boolean skipLocalVariableMapping = false;
		boolean renameInvalidLocals = false;
		NonClassCopyMode ncCopyMode = NonClassCopyMode.FIX_META_INF;
		TinyRemapper remapper = TinyRemapper.newRemapper()
				.withMappings(TinyUtils.createTinyMappingProvider(Paths.get("mappings.tater"), "obf", "intermediary"))
				.ignoreFieldDesc(ignoreFieldDesc)
				.withForcedPropagation(forcePropagation)
				.propagatePrivate(propagatePrivate)
				.removeFrames(removeFrames)
				.ignoreConflicts(ignoreConflicts)
				.checkPackageAccess(checkPackageAccess)
				.fixPackageAccess(fixPackageAccess)
				.resolveMissing(resolveMissing)
				.rebuildSourceFilenames(rebuildSourceFilenames)
				.skipLocalVariableMapping(skipLocalVariableMapping)
				.renameInvalidLocals(renameInvalidLocals)
				.build();
		try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(Paths.get("mapped.jar")).build()) {
			outputConsumer.addNonClassFiles(Paths.get("unmapped.jar"), ncCopyMode, remapper);

			remapper.readInputs(Paths.get("unmapped.jar"));
			remapper.readClassPath(new Path[0]);

			remapper.apply(outputConsumer);
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			remapper.finish();
		}
	}
}
