package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    private final ConcurrentLinkedQueue<int[]> fairnessQueueCardsSlots;
    private final ConcurrentLinkedQueue<Player> fairnessQueuePlayers;
    private final Object bothQueues = new Object();
    private boolean foundSet;
    private int[] currCardSlots;
    private Thread dealerThread;
    private final long practicallyZeroMS = 9;
    private final long actualZero = 0;
    public boolean placedCards = false;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        fairnessQueueCardsSlots = new ConcurrentLinkedQueue<>();
        fairnessQueuePlayers = new ConcurrentLinkedQueue<>();
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        dealerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");

        Thread [] playerThreads = new Thread[players.length];
        for(int i = 0 ; i< playerThreads.length; i++){
            playerThreads[i] = new Thread(players[i]);
            playerThreads[i].start();
        }

        while (!shouldFinish()) {
            placeCardsOnTable();
            updateTimerDisplay(true);
            timerLoop();
            removeAllCardsFromTable(); //TODO: remove all tokens on table and clear all queues because we update here thr table
        }
        announceWinners();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }



    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        //TODO: current problem with timer is when someone gets penalty the timer freezes - fix
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true;
        for(int i = players.length - 1; i >= 0 ; i--)
        //So that the player threads will be eliminated in reverse order to the order they were created by
            players[i].terminate();
        dealerThread.interrupt();
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0 || !checkIfSetExists();
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {

        //TODO: must fix the problem if two sets have some or all tokens that are going to be removed (identical sets or partially identical when one is the set that gonna be removed
        while(!foundSet & System.currentTimeMillis() < reshuffleTime){
            //synchronized (fairnessQueueCards) { this is definitely not good
                checkNextSet();
            //}
            updateTimerDisplay(false);
        }
        //TODO: allow other user to choose cards that are not in a current set that is being removed
        //TODO: something wrong doesnt remove other players tokens
        if(foundSet){
            placedCards = false;
            table.removeCardsAndTokensInSlots(currCardSlots);
            for (Player p : players) {
                table.removeTokens(p.id, currCardSlots);
                p.removeMyTokens(currCardSlots);
            }
            Iterator<int[]> fairnessQueuesIterator = fairnessQueueCardsSlots.iterator();
            boolean[] keepOrNot = new boolean[fairnessQueueCardsSlots.size()];
            for (boolean b : keepOrNot)
                b = false;
            int currPlaceInQueue = 0;
            while (fairnessQueuesIterator.hasNext()) {
                int[] currCardSlotSet = fairnessQueuesIterator.next();
                for (int currCardSlot : currCardSlots) {
                    for (int k : currCardSlotSet) {
                        if (currCardSlot == k) {
                            keepOrNot[currPlaceInQueue] = false;
                            break;
                        }
                    }
                }
                currPlaceInQueue++;
            }
            filterQueues(keepOrNot);
            foundSet = false;
            //currCardSlots = null;
            placeCardsOnTable();
            updateTimerDisplay(true);
       }
    }

    //TODO I really hope this works and doesnt fuck us up later, but this might be a cause for a lot of concurrency problems
    private void filterQueues(boolean[] keepOrNot) {
        synchronized (bothQueues) {
            ConcurrentLinkedQueue<int[]> queue1 = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<Player> queue2 = new ConcurrentLinkedQueue<>();
            int size = fairnessQueueCardsSlots.size();
            for (int i = 0; i < size; i++) {
                if (keepOrNot[i]) {
                    queue1.add(fairnessQueueCardsSlots.remove());
                    queue2.add(fairnessQueuePlayers.remove());
                } else {
                    fairnessQueueCardsSlots.remove();
                    fairnessQueuePlayers.remove();
                }
            }
            size = queue1.size();
            for (int i = 0; i < size; i++) {
                fairnessQueueCardsSlots.add(queue1.remove());
                fairnessQueuePlayers.add(queue2.remove());
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        int min = 0;
        for (int i = 0; i < table.slotToCard.length; i++) {
            if (table.slotToCard[i] == null) {
                int max = deck.size() - 1;
                int random_num = (int)Math.floor(Math.random()*(max-min+1)+min);
                Integer card = deck.remove(random_num);
                if (card != null)
                    table.placeCard(card, i);
            }
        }
        placedCards = true;
    }
    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        //TODO : check how to fix
        synchronized (this){
            try {
                this.wait(env.config.tableDelayMillis);
            } catch (InterruptedException ignored) {}
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(reset) {
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        }
        else env.ui.setCountdown((reshuffleTime - System.currentTimeMillis() > practicallyZeroMS) ? reshuffleTime - System.currentTimeMillis() : actualZero, reshuffleTime - System.currentTimeMillis() < env.config.turnTimeoutWarningMillis);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        placedCards = false;
        for(int i = 0; i < env.config.rows * env.config.columns; i++) {
            int cardValue = table.slotToCard[i];
            deck.add(cardValue);
            for(Player p : players){
                int[] slotToRemove = new int[1]; //TODO: REMOVE MAGIC NUMBER
                if(p.getTokenOnSlot()[i]){
                    slotToRemove[0]=i;
                }
                p.removeMyTokens(slotToRemove);
                //hey
            }
            table.removeCard(i);
        }
        fairnessQueueCardsSlots.clear();
        fairnessQueuePlayers.clear();

    }

    public void iGotASet(Player p, int[] cardSlots) {
        synchronized (bothQueues) {
            fairnessQueueCardsSlots.add(cardSlots);
            fairnessQueuePlayers.add(p);
            bothQueues.notifyAll();
        }
    }
    private void checkNextSet() { //changed some things here in order to be able to remove the cards
        try {
            int[] cardSlots;
            Player p;
            synchronized (bothQueues){
                cardSlots = fairnessQueueCardsSlots.remove();
                p = fairnessQueuePlayers.remove();
                bothQueues.notifyAll();
            }
            currCardSlots = cardSlots;

            int[] cardsAsArray = new int[cardSlots.length];
            for (int i = 0; i < cardSlots.length; i++) {
                cardsAsArray[i] = table.slotToCard[cardSlots[i]];
            }

            if (env.util.testSet(cardsAsArray))
            {
                p.sendMessage(Player.Message.POINT);
                foundSet = true;
                p.removeMyTokens(cardSlots);
            }

            else{
                p.sendMessage(Player.Message.PENALTY);
                foundSet = false; }
            //TODO this might not be needed
            synchronized (cardSlots){
                cardSlots.notifyAll();
            }


        } catch (NoSuchElementException ignored) {}

    }
    private boolean checkIfSetExists(){

        List<Integer> currentTable = new LinkedList<>();
        Collections.addAll(currentTable, table.slotToCard);
        for(int i = 0; i<table.slotToCard.length; i++){
            if(table.slotToCard[i] == null){
                return true;
            }
        }
        List<int[]> sets = env.util.findSets(currentTable,1);
        return !sets.isEmpty();
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        // collect winning players
        List<Integer> potentialWinners = new Vector<>();
        int maxScore = -1;
        //found the max score
        for(Player p : players){
            if(maxScore < p.score()) {
                potentialWinners.clear();
                maxScore = p.score();
                potentialWinners.add(p.id);
            }
            else if (maxScore == p.score())
                potentialWinners.add(p.id);
        }
        //created an array
        int [] winners = new int[potentialWinners.size()];
        for(int i = 0; i < potentialWinners.size(); i++){
            winners[i] = potentialWinners.get(i);
        }

        env.ui.announceWinner(winners);
    }
}
