package org.heigit.bigspatialdata.oshdb.v0_6.transform;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.function.LongToIntFunction;
import java.util.function.ToIntFunction;

import org.heigit.bigspatialdata.oshdb.v0_6.osm.OSMMember;
import org.heigit.bigspatialdata.oshdb.v0_6.osm.OSMNode;
import org.apache.orc.impl.SerializationUtils;
import org.heigit.bigspatialdata.oshdb.osm.OSMType;
import org.heigit.bigspatialdata.oshdb.v0_6.osm.OSMWay;
import org.heigit.bigspatialdata.oshdb.v0_6.transform.rx.Block;
import org.heigit.bigspatialdata.oshdb.tool.importer.extract.data.OsmPbfMeta;
import org.heigit.bigspatialdata.oshdb.tool.importer.util.TagToIdMapper;
import org.heigit.bigspatialdata.oshdb.util.OSHDBTag;
import org.heigit.bigspatialdata.oshdb.util.OSHDBTimestamp;
import org.heigit.bigspatialdata.oshdb.v0_6.OSHDB;
import org.heigit.bigspatialdata.oshdb.v0_6.impl.osh.OSHNodeReader;
import org.heigit.bigspatialdata.oshdb.v0_6.impl.osh.builder.OSHWaySerializer;
import org.heigit.bigspatialdata.oshdb.v0_6.impl.osh.OSHWayReader;
import org.heigit.bigspatialdata.oshdb.v0_6.impl.osm.OSMWayImpl;
import org.heigit.bigspatialdata.oshdb.v0_6.util.LongKeyValueSink;
import org.heigit.bigspatialdata.oshdb.v0_6.util.backref.RefToBackRefs;
import org.heigit.bigspatialdata.oshdb.v0_6.util.io.ByteBufferBackedInputStream;
import org.heigit.bigspatialdata.oshdb.v0_6.util.io.BytesSink;
import org.heigit.bigspatialdata.oshdb.v0_6.util.io.BytesSource;
import org.heigit.bigspatialdata.oshdb.v0_6.util.io.OSHGridSort;
import org.heigit.bigspatialdata.oshdb.v0_6.util.io.OSHStore;
import org.heigit.bigspatialdata.oshpbf.parser.osm.v0_6.Entity;
import org.heigit.bigspatialdata.oshpbf.parser.osm.v0_6.Way;
import org.heigit.bigspatialdata.oshpbf.parser.pbf.BlobToOSHIterator;
import org.heigit.bigspatialdata.oshpbf.parser.pbf.PbfBlob;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.Weigher;
import com.google.common.collect.PeekingIterator;

import io.reactivex.Flowable;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.io.FastByteArrayOutputStream;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

public class TransformerWay extends Transformer {

	private final FastByteArrayOutputStream out = new FastByteArrayOutputStream();

	private final BytesSink store;
	private final BytesSource nodeSource;
	private final OSHGridSort sort;
	private final PeekingIterator<RefToBackRefs> backRefsRelations;
	private final LoadingCache<Long, ByteBuffer> nodeCache;

	private final ObjectArrayList<OSMWay> versions = new ObjectArrayList<>();	
	private final OSHWaySerializer oshSerializer = new OSHWaySerializer(); 

	public TransformerWay(TagToIdMapper tagToId, BytesSink store, BytesSource nodeSource, PeekingIterator<RefToBackRefs> backRefsRelations, OSHGridSort sort) {
		super(tagToId);
		this.store = store;
		this.nodeSource = nodeSource;
		this.backRefsRelations = backRefsRelations;
		this.sort = sort;

		nodeCache = CacheBuilder.newBuilder().maximumWeight(1L * 1024L * 1024L * 1024L)
				.weigher(new Weigher<Long, ByteBuffer>() {

					@Override
					public int weigh(Long key, ByteBuffer value) {
						return value.limit();
					}
				}).build(new CacheLoader<Long, ByteBuffer>() {

					@Override
					public ByteBuffer load(Long key) throws Exception {
						return nodeSource.get(key.longValue());
					}
				});
	}
	
	private final OSHNodeReader oshNode = new OSHNodeReader();
	private final FastByteArrayOutputStream aux = new FastByteArrayOutputStream(8192);
	private final WritableByteChannel channel = Channels.newChannel(aux);
	private final LongArrayList memberOffsets = new LongArrayList();
	
	@Override
	protected void transformEntities(long id, OSMType type, List<Entity> entities,Block block) {		
		oshSerializer.reset();
		versions.ensureCapacity(entities.size());
		for (Entity entity : entities) {
			final Way way = (Way) entity;
			final OSMWay osm = getOSM(way);
			oshSerializer.addWay(osm);
			versions.add(osm);
		}
		
		final long[] relations;
		if(backRefsRelations.hasNext() && backRefsRelations.peek().ref == id){
			relations = backRefsRelations.next().backRefs;
		}else{
			relations = EMPTY_BACKREFS;
		}
		
		try {
			out.reset();
			
			long minLongitude, minLatitude, maxLongitude, maxLatitude;
			minLongitude = minLatitude = Long.MAX_VALUE;
			maxLongitude = maxLatitude = Long.MIN_VALUE;
			
			aux.reset();
			memberOffsets.clear();
			for(OSMMember m : oshSerializer.getMembers()){
				long mId = m.getId();
				ByteBuffer bytes;
				try {
					bytes = nodeCache.get(mId);
				} catch (ExecutionException e) {
					throw new IOException(e);
				}
				channel.write(bytes);
				bytes.rewind();
				oshNode.read(mId, bytes);
				minLongitude = Math.min(minLongitude, oshNode.getMinLongitude());
				minLatitude = Math.min(minLatitude, oshNode.getMinLatitude());
				maxLongitude = Math.max(maxLongitude, oshNode.getMaxLongitude());
				maxLatitude = Math.max(maxLatitude, oshNode.getMaxLatitude());
			}
			serUtil.writeVulong(out, aux.length());
			out.write(aux.array, 0, aux.length);
			oshSerializer.serialize(out, versions,minLongitude,minLatitude,maxLongitude,maxLatitude,memberOffsets,relations);
			ByteBuffer bytes = ByteBuffer.wrap(out.array, 0, out.length);

			if(!check(versions, id, bytes)){
				System.exit(2);
			}

			// long pos = store.write(id, bytes);
			//
			// if(!tagSet.isEmpty() && ((header & OSHDB.OSH_HEADER_INVALID) ==
			// 0)){
			// final long zId = zGrid.getIdSingleZIdWithZoom(minLongitude,
			// maxLongitude, minLatitude, maxLatitude);
			// final long midLongitude = maxLongitude - minLongitude;
			// final long midLatitude = maxLatitude - minLatitude;
			//
			// gridSort.add(new
			// WaySortKey(zId,midLongitude,midLatitude,id,pos));
			// }

		} catch (IOException e) {
			e.printStackTrace();
		} 
		versions.clear();
	}
	
	private final ByteBufferBackedInputStream byteBufferInput = new ByteBufferBackedInputStream();
	private boolean check(List<OSMWay> versions, long id, ByteBuffer bytes) throws IOException{
		OSHWayReader reader = new OSHWayReader();
		byteBufferInput.set(bytes);
		int nodeLength = (int) SerializationUtils.readVulong(byteBufferInput);
		ByteBuffer nodes = bytes.slice();
		bytes.position(bytes.position() + nodeLength);
		reader.read(id, bytes, nodes);

		boolean check = true;
		Iterator<OSMWay> expectedItr = versions.iterator();
		Iterator<OSMWay> actualItr = reader.iterator();
		while (expectedItr.hasNext() && actualItr.hasNext()) {
			OSMWay e = expectedItr.next();
			OSMWay a = actualItr.next();

		if (!e.equals(a)) {
				if(!e.isVisible() && !a.isVisible())
					continue;
				System.out.println("e: " + e);
				System.out.println("a: " + a);
				check = false;
			}
		}

		while (expectedItr.hasNext()) {
			System.out.println("e: " + expectedItr.next());
			System.out.println("a: missing");
			check = false;
		}
		while (actualItr.hasNext()) {
			System.out.println("e: missing");
			System.out.println("a: " + actualItr.next());
			check = false;
		}
		
		return check;
	}

	private OSMWay getOSM(Way entity) {
		return new OSMWayImpl(entity.getId() //
				, entity.getVersion() //
				, entity.isVisible()
				, new OSHDBTimestamp(entity.getTimestamp()) //
				, entity.getChangeset() //
				, entity.getUserId() //
				, getKeyValue(entity.getTags()) //
				, convertNodeIdsToOSMMembers(entity.getRefs()));
	}

	private List<OSMMember> convertNodeIdsToOSMMembers(long[] refs) {
		List<OSMMember> members = new ArrayList<OSMMember>(refs.length);
		for (long ref : refs) {
			members.add(new OSMMember(ref, OSMType.NODE, -1));
		}
		return members;
	}
}