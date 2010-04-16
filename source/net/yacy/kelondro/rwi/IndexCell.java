// ReverseIndexCell.java
// (C) 2009 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 1.3.2009 on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate: 2009-10-10 01:32:08 +0200 (Sa, 10 Okt 2009) $
// $LastChangedRevision: 6393 $
// $LastChangedBy: orbiter $
//
// LICENSE
// 
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package net.yacy.kelondro.rwi;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.Semaphore;

import de.anomic.yacy.graphics.ProfilingGraph;

import net.yacy.kelondro.data.meta.URIMetadataRow;
import net.yacy.kelondro.index.ARC;
import net.yacy.kelondro.index.HandleSet;
import net.yacy.kelondro.index.Row;
import net.yacy.kelondro.index.RowSpaceExceededException;
import net.yacy.kelondro.index.SimpleARC;
import net.yacy.kelondro.logging.Log;
import net.yacy.kelondro.order.ByteOrder;
import net.yacy.kelondro.order.CloneableIterator;
import net.yacy.kelondro.order.MergeIterator;
import net.yacy.kelondro.order.Order;
import net.yacy.kelondro.util.ByteArray;
import net.yacy.kelondro.util.EventTracker;
import net.yacy.kelondro.util.MemoryControl;


/*
 * an index cell is a part of the horizontal index in the new segment-oriented index
 * data structure of YaCy. If there is no filter in front of a cell, it might also be
 * the organization for a complete segment index. Each cell consists of a number of BLOB files, that
 * must be merged to represent a single index. In fact these index files are only merged on demand
 * if there are too many of them. An index merge can be done with a stream read and stream write operation.
 * in normal operation, there are only a number of read-only BLOB files and a single RAM cache that is
 * kept in the RAM as long as a given limit of entries is reached. Then the cache is flushed and becomes
 * another BLOB file in the index array.
 */

public final class IndexCell<ReferenceType extends Reference> extends AbstractBufferedIndex<ReferenceType> implements BufferedIndex<ReferenceType> {

    private static final long cleanupCycle =  60000;
    private static final long dumpCycle    = 600000;
    
    // class variables
    private final ReferenceContainerArray<ReferenceType> array;
    private       ReferenceContainerCache<ReferenceType> ram;
    private       int                                    maxRamEntries;
    private final IODispatcher                           merger;
    private       long                                   lastCleanup, lastDump;
    private final long                                   targetFileSize, maxFileSize;
    private final int                                    writeBufferSize;
    private final ARC<ByteArray, Integer>                countCache;
    private       Semaphore                              dumperSemaphore = new Semaphore(1);
    private       Semaphore                              cleanerSemaphore = new Semaphore(1);
    
    public IndexCell(
            final File cellPath,
            final String prefix,
            final ReferenceFactory<ReferenceType> factory,
            final ByteOrder termOrder,
            final Row payloadrow,
            final int maxRamEntries,
            final long targetFileSize,
            final long maxFileSize,
            IODispatcher merger,
            int writeBufferSize
            ) throws IOException {
        super(factory);
        
        this.array = new ReferenceContainerArray<ReferenceType>(cellPath, prefix, factory, termOrder, payloadrow, merger);
        this.ram = new ReferenceContainerCache<ReferenceType>(factory, payloadrow, termOrder);
        this.maxRamEntries = maxRamEntries;
        this.merger = merger;
        this.lastCleanup = System.currentTimeMillis();
        this.lastDump = System.currentTimeMillis();
        this.targetFileSize = targetFileSize;
        this.maxFileSize = maxFileSize;
        this.writeBufferSize = writeBufferSize;
        this.countCache = new SimpleARC<ByteArray, Integer>(1000);
        //cleanCache();
    }

    
    /*
     * methods to implement Index
     */
    
    /**
     * add entries to the cell: this adds the new entries always to the RAM part, never to BLOBs
     * @throws IOException 
     * @throws RowSpaceExceededException
     */
    public void add(ReferenceContainer<ReferenceType> newEntries) throws IOException, RowSpaceExceededException {
        try {
            this.ram.add(newEntries);
            long t = System.currentTimeMillis();
            if (this.ram.size() % 1000 == 0 || this.lastCleanup + cleanupCycle < t || this.lastDump + dumpCycle < t) {
                EventTracker.update("wordcache", Long.valueOf(this.ram.size()), true, 30000, ProfilingGraph.maxTime);
                cleanCache();
            }
        } catch (RowSpaceExceededException e) {
            EventTracker.update("wordcache", Long.valueOf(this.ram.size()), true, 30000, ProfilingGraph.maxTime);
            cleanCache();
            this.ram.add(newEntries);
        }
        
    }

    public void add(byte[] termHash, ReferenceType entry) throws IOException, RowSpaceExceededException {
        try {
            this.ram.add(termHash, entry);
            long t = System.currentTimeMillis();
            if (this.ram.size() % 1000 == 0 || this.lastCleanup + cleanupCycle < t || this.lastDump + dumpCycle < t) {
                EventTracker.update("wordcache", Long.valueOf(this.ram.size()), true, 30000, ProfilingGraph.maxTime);
                cleanCache();
            }
        } catch (RowSpaceExceededException e) {
            EventTracker.update("wordcache", Long.valueOf(this.ram.size()), true, 30000, ProfilingGraph.maxTime);
            cleanCache();
            this.ram.add(termHash, entry);
        }
    }

    /**
     * checks if there is any container for this termHash, either in RAM or any BLOB
     */
    public boolean has(byte[] termHash) {
        if (this.ram.has(termHash)) return true;
        return this.array.has(termHash);
    }

    /**
     * count number of references for a given term
     * this method may cause strong IO load if called too frequently, because it is
     * necessary to read the corresponding reference containers from the files and
     * count the resulting index object.
     * To reduce the load for processes that frequently need access to the same
     * term objects, a ARC cache is here to reduce IO load.
     */
    public int count(byte[] termHash) {
        
        // check if value is in cache
        ByteArray ba = new ByteArray(termHash);
        Integer countCache = this.countCache.get(ba);
        int countFile;
        if (countCache == null) {
            // read fresh values from file
            ReferenceContainer<ReferenceType> c1;
            try {
                c1 = this.array.get(termHash);
            } catch (Exception e) {
                Log.logException(e);
                c1 = null;
            }
            countFile = (c1 == null) ? 0 : c1.size();
            
            // store to cache
            this.countCache.put(ba, countFile);
        } else {
            // value was in ram
            countFile = countCache.intValue();
        }
        
        // count from container in ram
        ReferenceContainer<ReferenceType> countRam = this.ram.get(termHash, null);

        return (countRam == null) ? countFile : countFile + countRam.size();
    }
    
    /**
     * all containers in the BLOBs and the RAM are merged and returned.
     * Please be aware that the returned values may be top-level cloned ReferenceContainers or direct links to containers
     * If the containers are modified after they are returned, they MAY alter the stored index.
     * @throws IOException
     * @return a container with merged ReferenceContainer from RAM and the file array or null if there is no data to be returned
     */
    public ReferenceContainer<ReferenceType> get(byte[] termHash, HandleSet urlselection) throws IOException {
        ReferenceContainer<ReferenceType> c0 = this.ram.get(termHash, null);
        ReferenceContainer<ReferenceType> c1 = null;
        try {
            c1 = this.array.get(termHash);
        } catch (RowSpaceExceededException e2) {
            Log.logException(e2);
        }
        if (c1 == null) {
            if (c0 == null) return null;
            return c0;
        }
        if (c0 == null) return c1;
        try {
            return c1.merge(c0);
        } catch (RowSpaceExceededException e) {
            // try to free some ram
            countCache.clear();
            try {
                return c1.merge(c0);
            } catch (RowSpaceExceededException e1) {
                // go silently over the problem
                return (c1.size() > c0.size()) ? c1: c0;
            }
        }
    }

    /**
     * deleting a container affects the containers in RAM and all the BLOB files
     * the deleted containers are merged and returned as result of the method
     * @throws IOException 
     */
    public ReferenceContainer<ReferenceType> delete(byte[] termHash) throws IOException {
        ReferenceContainer<ReferenceType> c1 = null;
        try {
            c1 = this.array.get(termHash);
        } catch (RowSpaceExceededException e2) {
            Log.logException(e2);
        }
        if (c1 != null) {
            this.array.delete(termHash);
            this.countCache.remove(new ByteArray(termHash));
        }
        ReferenceContainer<ReferenceType> c0 = this.ram.delete(termHash);
        cleanCache();
        if (c1 == null) return c0;
        if (c0 == null) return c1;
        try {
            return c1.merge(c0);
        } catch (RowSpaceExceededException e) {
            // try to free some ram
            countCache.clear();
            try {
                return c1.merge(c0);
            } catch (RowSpaceExceededException e1) {
                // go silently over the problem
                return (c1.size() > c0.size()) ? c1: c0;
            }
        }
    }
    
    /**
     * remove url references from a selected word hash. this deletes also in the BLOB
     * files, which means that there exists new gap entries after the deletion
     * The gaps are never merged in place, but can be eliminated when BLOBs are merged into
     * new BLOBs. This returns the sum of all url references that have been removed
     * @throws IOException 
     */
    public int remove(byte[] termHash, HandleSet urlHashes) throws IOException {
        int removed = this.ram.remove(termHash, urlHashes);
        int reduced = this.array.replace(termHash, new RemoveRewriter<ReferenceType>(urlHashes));
        this.countCache.remove(new ByteArray(termHash));
        return removed + (reduced / this.array.rowdef().objectsize);
    }

    public int remove(byte[] termHash, Set<String> urlHashes) throws IOException {
        int removed = this.ram.remove(termHash, urlHashes);
        int reduced = this.array.replace(termHash, new RemoveRewriter<ReferenceType>(urlHashes));
        this.countCache.remove(new ByteArray(termHash));
        return removed + (reduced / this.array.rowdef().objectsize);
    }

    public boolean remove(byte[] termHash, byte[] urlHashBytes) throws IOException {
        boolean removed = this.ram.remove(termHash, urlHashBytes);
        int reduced = this.array.replace(termHash, new RemoveRewriter<ReferenceType>(urlHashBytes));
        this.countCache.remove(new ByteArray(termHash));
        return removed || (reduced > 0);
    }

    private static class RemoveRewriter<ReferenceType extends Reference> implements ReferenceContainerArray.ContainerRewriter<ReferenceType> {
        
        HandleSet urlHashes;
        
        public RemoveRewriter(HandleSet urlHashes) {
            this.urlHashes = urlHashes;
        }
        
        public RemoveRewriter(Set<String> urlHashes) {
            this.urlHashes = new HandleSet(URIMetadataRow.rowdef.primaryKeyLength, URIMetadataRow.rowdef.objectOrder, 0);
            for (String s: urlHashes)
                try {
                    this.urlHashes.put(s.getBytes());
                } catch (RowSpaceExceededException e) {
                    Log.logException(e);
                }
        }
        
        public RemoveRewriter(byte[] urlHashBytes) {
            this.urlHashes = new HandleSet(URIMetadataRow.rowdef.primaryKeyLength, URIMetadataRow.rowdef.objectOrder, 0);
            try {
                this.urlHashes.put(urlHashBytes);
            } catch (RowSpaceExceededException e) {
                Log.logException(e);
            }
        }
        
        public ReferenceContainer<ReferenceType> rewrite(ReferenceContainer<ReferenceType> container) {
            container.removeEntries(urlHashes);
            return container;
        }
        
    }

    public CloneableIterator<ReferenceContainer<ReferenceType>> references(byte[] starttermHash, boolean rot) {
        final Order<ReferenceContainer<ReferenceType>> containerOrder = new ReferenceContainerOrder<ReferenceType>(factory, this.ram.rowdef().getOrdering().clone());
        containerOrder.rotate(new ReferenceContainer<ReferenceType>(factory, starttermHash));
        return new MergeIterator<ReferenceContainer<ReferenceType>>(
            this.ram.references(starttermHash, rot),
            new MergeIterator<ReferenceContainer<ReferenceType>>(
                this.ram.references(starttermHash, false),
                this.array.wordContainerIterator(starttermHash, false),
                containerOrder,
                ReferenceContainer.containerMergeMethod,
                true),
            containerOrder,
            ReferenceContainer.containerMergeMethod,
            true);
    }

    public CloneableIterator<ReferenceContainer<ReferenceType>> references(byte[] startTermHash, boolean rot, boolean ram) {
        final Order<ReferenceContainer<ReferenceType>> containerOrder = new ReferenceContainerOrder<ReferenceType>(factory, this.ram.rowdef().getOrdering().clone());
        containerOrder.rotate(new ReferenceContainer<ReferenceType>(factory, startTermHash));
        if (ram) {
            return this.ram.references(startTermHash, rot);
        }
        return new MergeIterator<ReferenceContainer<ReferenceType>>(
                this.ram.references(startTermHash, false),
                this.array.wordContainerIterator(startTermHash, false),
                containerOrder,
                ReferenceContainer.containerMergeMethod,
                true);
    }

    /**
     * clear the RAM and BLOB part, deletes everything in the cell
     * @throws IOException 
     */
    public synchronized void clear() throws IOException {
        this.ram.clear();
        this.array.clear();
        this.countCache.clear();
    }
    
    /**
     * when a cell is closed, the current RAM is dumped to a file which will be opened as
     * BLOB file the next time a cell is opened. A name for the dump is automatically generated
     * and is composed of the current date and the cell salt
     */
    public synchronized void close() {
        if (!this.ram.isEmpty()) this.ram.dump(this.array.newContainerBLOBFile(), (int) Math.min(MemoryControl.available() / 3, writeBufferSize));
        // close all
        this.ram.close();
        this.array.close();
        this.countCache.clear();
    }

    public int size() {
        throw new UnsupportedOperationException("an accumulated size of index entries would not reflect the real number of words, which cannot be computed easily");
    }

    public int[] sizes() {
        int[] as = this.array.sizes();
        int[] asr = new int[as.length + 1];
        System.arraycopy(as, 0, asr, 0, as.length);
        asr[as.length] = this.ram.size();
        return asr;
    }
    
    public int sizesMax() {
        int m = 0;
        int[] s = sizes();
        for (int i = 0; i < s.length; i++) if (s[i] > m) m = s[i];
        return m;
    }

    public int minMem() {
        return 10 * 1024 * 1024;
    }

    public ByteOrder ordering() {
        return this.array.ordering();
    }
    
    
    /*
     * cache control methods
     */
    
    private void cleanCache() {
        this.countCache.clear();
        
        // dump the cache if necessary
        long t = System.currentTimeMillis();
        if (this.dumperSemaphore.availablePermits() > 0 &&
            (this.ram.size() >= this.maxRamEntries ||
             (this.ram.size() > 3000 && !MemoryControl.request(80L * 1024L * 1024L, false)) ||
             (this.ram.size() > 3000 && this.lastDump + dumpCycle < t))) {
            try {
                this.dumperSemaphore.acquire(); // only one may pass
                if (this.ram.size() >= this.maxRamEntries ||
                    (this.ram.size() > 3000 && !MemoryControl.request(80L * 1024L * 1024L, false)) ||
                    (this.ram.size() > 0 && this.lastDump + dumpCycle < t)) try {
                    this.lastDump = System.currentTimeMillis();
                    // dump the ram
                    File dumpFile = this.array.newContainerBLOBFile();
                    // a critical point: when the ram is handed to the dump job,
                    // don't write into it any more. Use a fresh one instead
                    ReferenceContainerCache<ReferenceType> ramdump;
                    synchronized (this) {
                        ramdump = this.ram;
                        // get a fresh ram cache
                        this.ram = new ReferenceContainerCache<ReferenceType>(factory, this.array.rowdef(), this.array.ordering());
                    }
                    // dump the buffer
                    merger.dump(ramdump, dumpFile, array);
                    this.lastDump = System.currentTimeMillis();
                } catch (Exception e) {
                    // catch all exceptions to prevent that no semaphore is released
                    Log.logException(e);
                }
                this.dumperSemaphore.release();
            } catch (InterruptedException e) {
                Log.logException(e);
            }
        }
        
        // clean-up the cache
        if (this.cleanerSemaphore.availablePermits() > 0 &&
            (this.array.entries() > 50 ||
             this.lastCleanup + cleanupCycle < t)) {
            try {
                this.cleanerSemaphore.acquire();
                if (this.array.entries() > 50 || (this.lastCleanup + cleanupCycle < System.currentTimeMillis())) try {
                    this.lastCleanup = System.currentTimeMillis(); // set time to prevent that this is called to soo again
                    this.array.shrink(this.targetFileSize, this.maxFileSize);
                    this.lastCleanup = System.currentTimeMillis(); // set again to mark end of procedure
                } catch (Exception e) {
                    // catch all exceptions to prevent that no semaphore is released
                    Log.logException(e);
                }
                this.cleanerSemaphore.release();
            } catch (InterruptedException e) {
                Log.logException(e);
            }
        }
    }
    
    
    public File newContainerBLOBFile() {
        // for migration of cache files
        return this.array.newContainerBLOBFile();
    }
    
    public void mountBLOBFile(File blobFile) throws IOException {
        // for migration of cache files
        this.array.mountBLOBFile(blobFile);
    }

    public long getBufferMaxAge() {
        return System.currentTimeMillis();
    }

    public int getBufferMaxReferences() {
        return this.ram.maxReferences();
    }

    public long getBufferMinAge() {
        return System.currentTimeMillis();
    }

    public int getBufferSize() {
        return this.ram.size();
    }

    public long getBufferSizeBytes() {
        return 10000 * this.ram.size(); // guessed; we don't know that exactly because there is no statistics here (expensive, not necessary)
    }

    public void setBufferMaxWordCount(int maxWords) {
        this.maxRamEntries = maxWords;
        this.cleanCache();
    }    

}
