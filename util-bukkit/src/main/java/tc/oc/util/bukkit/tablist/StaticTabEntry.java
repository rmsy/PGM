package tc.oc.util.bukkit.tablist;

import net.md_5.bungee.api.chat.BaseComponent;
import tc.oc.util.bukkit.component.Component;

public class StaticTabEntry extends SimpleTabEntry {

  private final Component content;

  public StaticTabEntry(Component content) {
    this.content = content;
  }

  @Override
  public void addToView(TabView view) {}

  @Override
  public void removeFromView(TabView view) {}

  @Override
  public boolean isDirty(TabView view) {
    return false;
  }

  @Override
  public void markClean(TabView view) {}

  @Override
  public BaseComponent getContent(TabView view) {
    return content.render(view.getViewer());
  }
}
