// yacySearch.java 
// -------------------------------------
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.anomic.de
// Frankfurt, Germany, 2004
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
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

package de.anomic.yacy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import de.anomic.crawler.ResultURLs;
import de.anomic.data.Blacklist;
import de.anomic.kelondro.order.Bitfield;
import de.anomic.kelondro.util.ScoreCluster;
import de.anomic.kelondro.util.Log;
import de.anomic.plasma.plasmaSearchQuery;
import de.anomic.plasma.plasmaSearchRankingProcess;
import de.anomic.plasma.plasmaSearchRankingProfile;
import de.anomic.plasma.plasmaWordIndex;
import de.anomic.yacy.dht.PeerSelection;

public class yacySearch extends Thread {

    final private String wordhashes, excludehashes, urlhashes;
    final private boolean global;
    final private int partitions;
    final private plasmaWordIndex wordIndex;
    final private plasmaSearchRankingProcess containerCache;
    final private Map<String, TreeMap<String, String>> abstractCache;
    final private Blacklist blacklist;
    final private yacySeed targetPeer;
    private String[] urls;
    private final int count, maxDistance;
    final private plasmaSearchRankingProfile rankingProfile;
    final private String prefer, filter, language;
    final private Bitfield constraint;
    
    ResultURLs crawlResults;
    
    public yacySearch(final String wordhashes, final String excludehashes, final String urlhashes,
                      final String prefer, final String filter, final String language,
                      final int count, final int maxDistance, 
                      final boolean global, final int partitions, final yacySeed targetPeer, final plasmaWordIndex wordIndex,
                      final ResultURLs crawlResults,
                      final plasmaSearchRankingProcess containerCache,
                      final Map<String, TreeMap<String, String>> abstractCache,
                      final Blacklist blacklist,
                      final plasmaSearchRankingProfile rankingProfile,
                      final Bitfield constraint) {
        super("yacySearch_" + targetPeer.getName());
        //System.out.println("DEBUG - yacySearch thread " + this.getName() + " initialized " + ((urlhashes.length() == 0) ? "(primary)" : "(secondary)"));
        this.wordhashes = wordhashes;
        this.excludehashes = excludehashes;
        this.urlhashes = urlhashes;
        this.prefer = prefer;
        this.filter = filter;
        this.language = language;
        this.global = global;
        this.partitions = partitions;
        this.wordIndex = wordIndex;
        this.crawlResults = crawlResults;
        this.containerCache = containerCache;
        this.abstractCache = abstractCache;
        this.blacklist = blacklist;
        this.targetPeer = targetPeer;
        this.urls = null;
        this.count = count;
        this.maxDistance = maxDistance;
        this.rankingProfile = rankingProfile;
        this.constraint = constraint;
    }

    public void run() {
        try {
            this.urls = yacyClient.search(
                        wordIndex.peers().mySeed(),
                        wordhashes, excludehashes, urlhashes, prefer, filter, language, count, maxDistance, global, partitions,
                        targetPeer, wordIndex, crawlResults, containerCache, abstractCache,
                        blacklist, rankingProfile, constraint);
            if (urls != null) {
                // urls is an array of url hashes. this is only used for log output
                final StringBuilder urllist = new StringBuilder(this.urls.length * 13);
                for (int i = 0; i < this.urls.length; i++) urllist.append(this.urls[i]).append(' ');
                yacyCore.log.logInfo("REMOTE SEARCH - remote peer " + targetPeer.hash + ":" + targetPeer.getName() + " contributed " + urls.length + " links for word hash " + wordhashes + ": " + new String(urllist));
                wordIndex.peers().mySeed().incRI(urls.length);
                wordIndex.peers().mySeed().incRU(urls.length);
            } else {
                yacyCore.log.logInfo("REMOTE SEARCH - no answer from remote peer " + targetPeer.hash + ":" + targetPeer.getName());
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }

    public static String set2string(final TreeSet<byte[]> hashes) {
        String wh = "";
        final Iterator<byte[]> iter = hashes.iterator();
        while (iter.hasNext()) { wh = wh + new String(iter.next()); }
        return wh;
    }

    public int links() {
        return this.urls.length;
    }
    
    public int count() {
        return this.count;
    }
    
    public yacySeed target() {
        return targetPeer;
    }

    private static yacySeed[] selectClusterPeers(final yacySeedDB seedDB, final TreeMap<byte[], String> peerhashes) {
    	final Iterator<Map.Entry<byte[], String>> i = peerhashes.entrySet().iterator();
    	final ArrayList<yacySeed> l = new ArrayList<yacySeed>();
    	Map.Entry<byte[], String> entry;
    	yacySeed s;
    	while (i.hasNext()) {
    		entry = i.next();
    		s = seedDB.get(new String(entry.getKey())); // should be getConnected; get only during testing time
    		if (s != null) {
    			s.setAlternativeAddress(entry.getValue());
    			l.add(s);
    		}
    	}
    	final yacySeed[] result = new yacySeed[l.size()];
    	for (int j = 0; j < l.size(); j++) {
    		result[j] = l.get(j);
    	}
    	return result;
    	//return (yacySeed[]) l.toArray();
    }
    
    private static yacySeed[] selectSearchTargets(final yacySeedDB seedDB, final TreeSet<byte[]> wordhashes, int seedcount, int redundancy) {
        // find out a specific number of seeds, that would be relevant for the given word hash(es)
        // the result is ordered by relevance: [0] is most relevant
        // the seedcount is the maximum number of wanted results
        if (seedDB == null) { return null; }
        if ((seedcount >= seedDB.sizeConnected()) || (seedDB.noDHTActivity())) {
            seedcount = seedDB.sizeConnected();
        }
        
        // put in seeds according to dht
        final ScoreCluster<String> ranking = new ScoreCluster<String>();
        final HashMap<String, yacySeed> regularSeeds = new HashMap<String, yacySeed>();
        final HashMap<String, yacySeed> matchingSeeds = new HashMap<String, yacySeed>();
        yacySeed seed;
        Iterator<yacySeed> dhtEnum;         
        Iterator<byte[]> iter = wordhashes.iterator();
        while (iter.hasNext()) {
            PeerSelection.selectDHTPositions(seedDB, iter.next(), redundancy, regularSeeds, ranking);
        }

        // put in seeds according to size of peer
        dhtEnum = seedDB.seedsSortedConnected(false, yacySeed.ICOUNT);
        int c = Math.min(seedDB.sizeConnected(), seedcount);
        int score;
        while (dhtEnum.hasNext() && c > 0) {
            seed = dhtEnum.next();
            if (seed == null) continue;
            if (!seed.getFlagAcceptRemoteIndex()) continue; // probably a robinson peer
            score = (int) Math.round(Math.random() * ((c / 3) + 3));
            if (Log.isFine("PLASMA")) Log.logFine("PLASMA", "selectPeers/RWIcount: " + seed.hash + ":" + seed.getName() + ", RWIcount=" + seed.get(yacySeed.ICOUNT,"") + ", score " + score);
            ranking.addScore(seed.hash, score);
            regularSeeds.put(seed.hash, seed);
            c--;
        }

        // put in seeds that are public robinson peers and where the peer tags match with query
        // or seeds that are newbies to ensure that public demonstrations always work
        dhtEnum = seedDB.seedsConnected(true, false, null, (float) 0.50);
        while (dhtEnum.hasNext()) {
        	seed = dhtEnum.next();
            if (seed == null) continue;
            if (seed.matchPeerTags(wordhashes)) {
                Log.logInfo("PLASMA", "selectPeers/PeerTags: " + seed.hash + ":" + seed.getName() + ", is specialized peer for " + seed.getPeerTags().toString());
                regularSeeds.remove(seed.hash);
                ranking.deleteScore(seed.hash);
                matchingSeeds.put(seed.hash, seed);
            } else if (seed.getFlagAcceptRemoteIndex() && seed.getAge() < 1) { // the 'workshop feature'
                Log.logInfo("PLASMA", "selectPeers/Age: " + seed.hash + ":" + seed.getName() + ", is newbie, age = " + seed.getAge());
                regularSeeds.remove(seed.hash);
                ranking.deleteScore(seed.hash);
                matchingSeeds.put(seed.hash, seed);
            }
        }
        
        // evaluate the ranking score and select seeds
        seedcount = Math.min(ranking.size(), seedcount);
        final yacySeed[] result = new yacySeed[seedcount + matchingSeeds.size()];
        c = 0;
        Iterator<String> iters = ranking.scores(false); // higher are better
        while (iters.hasNext() && c < seedcount) {
            seed = regularSeeds.get(iters.next());
            seed.selectscore = c;
            Log.logInfo("PLASMA", "selectPeers/_dht_: " + seed.hash + ":" + seed.getName() + " is choice " + c);
            result[c++] = seed;
        }
        for (final yacySeed s: matchingSeeds.values()) {
            s.selectscore = c;
            Log.logInfo("PLASMA", "selectPeers/_match_: " + s.hash + ":" + s.getName() + " is choice " + c);
            result[c++] = s;
        }

//      System.out.println("DEBUG yacySearch.selectPeers = " + seedcount + " seeds:"); for (int i = 0; i < seedcount; i++) System.out.println(" #" + i + ":" + result[i]); // debug
        return result;
    }

    public static yacySearch[] primaryRemoteSearches(
            final String wordhashes, final String excludehashes, final String urlhashes,
            final String prefer, final String filter, String language,
            final int count, final int maxDist,
            final plasmaWordIndex wordIndex,
            final ResultURLs crawlResults,
            final plasmaSearchRankingProcess containerCache,
            final Map<String, TreeMap<String, String>> abstractCache,
            int targets,
            final Blacklist blacklist,
            final plasmaSearchRankingProfile rankingProfile,
            final Bitfield constraint,
            final TreeMap<byte[], String> clusterselection) {
        // check own peer status
        //if (wordIndex.seedDB.mySeed() == null || wordIndex.seedDB.mySeed().getPublicAddress() == null) { return null; }

        // prepare seed targets and threads
        assert language != null;
        final yacySeed[] targetPeers =
            (clusterselection == null) ?
                    selectSearchTargets(
                            wordIndex.peers(),
                            plasmaSearchQuery.hashes2Set(wordhashes),
                            targets,
                            wordIndex.peers().redundancy())
                  : selectClusterPeers(wordIndex.peers(), clusterselection);
        if (targetPeers == null) return new yacySearch[0];
        targets = targetPeers.length;
        if (targets == 0) return new yacySearch[0];
        final yacySearch[] searchThreads = new yacySearch[targets];
        for (int i = 0; i < targets; i++) {
            if (targetPeers[i] == null || targetPeers[i].hash == null) continue;
            searchThreads[i] = new yacySearch(wordhashes, excludehashes, urlhashes, prefer, filter, language, count, maxDist, true, targets, targetPeers[i],
                    wordIndex, crawlResults, containerCache, abstractCache, blacklist, rankingProfile, constraint);
            searchThreads[i].start();
            //try {Thread.sleep(20);} catch (InterruptedException e) {}
        }
        return searchThreads;
    }
    
    public static yacySearch secondaryRemoteSearch(
            final String wordhashes, final String excludehashes, final String urlhashes,
            final plasmaWordIndex wordIndex,
            final ResultURLs crawlResults,
            final plasmaSearchRankingProcess containerCache,
            final String targethash, final Blacklist blacklist,
            final plasmaSearchRankingProfile rankingProfile,
            final Bitfield constraint, final TreeMap<byte[], String> clusterselection) {
        // check own peer status
        if (wordIndex.peers().mySeed() == null || wordIndex.peers().mySeed().getPublicAddress() == null) { return null; }

        // prepare seed targets and threads
        final yacySeed targetPeer = wordIndex.peers().getConnected(targethash);
        if (targetPeer == null || targetPeer.hash == null) return null;
        if (clusterselection != null) targetPeer.setAlternativeAddress(clusterselection.get(targetPeer.hash.getBytes()));
        final yacySearch searchThread = new yacySearch(wordhashes, excludehashes, urlhashes, "", "", "en", 0, 9999, true, 0, targetPeer,
                                             wordIndex, crawlResults, containerCache, new TreeMap<String, TreeMap<String, String>>(), blacklist, rankingProfile, constraint);
        searchThread.start();
        return searchThread;
    }
    
    public static int remainingWaiting(final yacySearch[] searchThreads) {
        if (searchThreads == null) return 0;
        int alive = 0;
        for (int i = 0; i < searchThreads.length; i++) {
            if (searchThreads == null) break; // may occur
            if (searchThreads[i].isAlive()) alive++;
        }
        return alive;
    }
    
    public static int collectedLinks(final yacySearch[] searchThreads) {
        int links = 0;
        for (int i = 0; i < searchThreads.length; i++) {
            if (!(searchThreads[i].isAlive())) links += searchThreads[i].urls.length;
        }
        return links;
    }
    
    public static void interruptAlive(final yacySearch[] searchThreads) {
        for (int i = 0; i < searchThreads.length; i++) {
            if (searchThreads[i].isAlive()) searchThreads[i].interrupt();
        }
    }
    
}
