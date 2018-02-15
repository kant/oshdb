package org.heigit.bigspatialdata.oshdb.util;

public class OSHDBTagKey {
  private int key;

  public OSHDBTagKey(int key) {
    this.key = key;
  }

  public int toInt() {
    return this.key;
  }

  public boolean isPresentInKeytables() {
    return this.key >= 0;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof OSHDBTagKey && ((OSHDBTagKey)o).key == this.key;
  }

  @Override
  public int hashCode() {
    return this.key;
  }

  @Override
  public String toString() {
    return Integer.toString(this.key);
  }
}
