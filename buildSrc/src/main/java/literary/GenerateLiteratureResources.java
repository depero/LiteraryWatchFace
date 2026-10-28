package literary;

import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Build-time only: no parser or executable code is shipped in the watch face. */
@DisableCachingByDefault(because = "Conservative font fitting uses local system font metrics")
public abstract class GenerateLiteratureResources extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getLiteratureFile();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getTemplateFile();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void generate() throws IOException {
        LiteratureGenerator.Result result = LiteratureGenerator.generate(
                getLiteratureFile().get().getAsFile().toPath(),
                getTemplateFile().get().getAsFile().toPath(),
                getOutputDirectory().get().getAsFile().toPath(),
                getReportFile().get().getAsFile().toPath());
        getLogger().lifecycle("Literature: {} passages, {}/1440 minutes; {} fallback minutes. Report: {}",
                result.passageCount(), result.coveredMinutes(), result.missingTimes().size(),
                getReportFile().get().getAsFile());
    }
}
