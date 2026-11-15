package ru.bccontrol.sedonastudio.storage;

import java.util.Map;
import ru.bccontrol.sedonastudio.model.Alarms;
import ru.bccontrol.sedonastudio.model.Archive;
import ru.bccontrol.sedonastudio.model.Project;
import ru.bccontrol.sedonastudio.model.ProjectExtension;
import ru.bccontrol.sedonastudio.model.ScreenBindings;
import ru.bccontrol.sedonastudio.model.Screens;

/**
 * The project parts of the SCADA module: operator screens ("ui", the key the web editor uses too),
 * alarm rules ("alarms") and history channels ("archive"). Named in the module jar's
 * META-INF/services; an edition without that jar keeps these parts as they came.
 */
public final class ScadaParts {
  private ScadaParts() {}

  public static final class ScreensPart implements ProjectExtension {
    @Override
    public String key() {
      return Screens.KEY;
    }

    @Override
    public Project.Part decode(Map<?, ?> doc) {
      return ScreenStore.decode(doc);
    }

    @Override
    public Object encode(Project.Part part) {
      return ScreenStore.encode((Screens) part);
    }

    @Override
    public void follow(Project before, Project after) {
      ScreenBindings.follow(before, after);
    }
  }

  public static final class AlarmsPart implements ProjectExtension {
    @Override
    public String key() {
      return Alarms.KEY;
    }

    @Override
    public Project.Part decode(Map<?, ?> doc) {
      return AlarmStore.decode(doc);
    }

    @Override
    public Object encode(Project.Part part) {
      Alarms alarms = (Alarms) part;
      return alarms.rules.isEmpty() ? null : AlarmStore.encode(alarms);
    }

    @Override
    public void follow(Project before, Project after) {
      Alarms.follow(before, after);
    }
  }

  public static final class ArchivePart implements ProjectExtension {
    @Override
    public String key() {
      return Archive.KEY;
    }

    @Override
    public Project.Part decode(Map<?, ?> doc) {
      return ArchiveStore.decode(doc);
    }

    @Override
    public Object encode(Project.Part part) {
      Archive archive = (Archive) part;
      return archive.channels.isEmpty() ? null : ArchiveStore.encode(archive);
    }

    @Override
    public void follow(Project before, Project after) {
      Archive.follow(before, after);
    }
  }
}
