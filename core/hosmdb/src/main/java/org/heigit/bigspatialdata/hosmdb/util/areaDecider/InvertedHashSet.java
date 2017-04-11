package org.heigit.bigspatialdata.hosmdb.util.areaDecider;

import java.util.HashSet;
import java.util.Set;

/**
 * Negated Set: contains(x) returns true only if x has not been add()ed to the inverted set previously.
 * Useful to supply where a method expects a whitelist "set", but one has a blacklist of values instead. (or vv)
 */
public class InvertedHashSet<E> extends HashSet<E> implements Set<E> {
	@Override
	public boolean isEmpty() {
		return false;
	}

	@Override
	public boolean contains(Object o) {
		return !super.contains(o);
	}
}
