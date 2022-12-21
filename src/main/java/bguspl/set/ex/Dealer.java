package bguspl.set.ex;
import bguspl.set.Env;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
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

    private final BlockingQueue<int[]> fairnessQueueCardsSlots;
    private final BlockingQueue<Player> fairnessQueuePlayers;
    private final BlockingQueue<Thread> fairnessTerminatingSequence;
    private final Object bothQueues = new Object();
    private boolean foundSet;
    private int[] currCardSlots;
    private Thread dealerThread;
    private final long practicallyZeroMS = 9;
    private final long actualZero = 0;
    public boolean placedCards = false;
    private boolean reverseTimer;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        fairnessQueueCardsSlots = new LinkedBlockingQueue<>();
        fairnessQueuePlayers = new LinkedBlockingQueue<>();
        fairnessTerminatingSequence = new LinkedBlockingQueue<>();
        reverseTimer = env.config.turnTimeoutMillis <= actualZero; //bonus 3
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        dealerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
        Thread[] playerThreads = new Thread[env.config.players];
        for(int i = 0 ; i< env.config.players; i++){
            playerThreads[i] = new Thread(players[i],Player.playerThreadName+"-"+i);
            playerThreads[i].start();
        }

        while (!shouldFinish()) {
            placeCardsOnTable();
            updateTimerDisplay(true);
            timerLoop();
            //if (terminate) break;
            removeAllCardsFromTable();
        }
        announceWinners();
        terminatePlayers();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (checkTableForSets() && !terminate && ((System.currentTimeMillis() < reshuffleTime && !reverseTimer) || reverseTimer && System.currentTimeMillis() >= reshuffleTime)) {
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
    }

    private void terminatePlayers() {
        int size = fairnessTerminatingSequence.size();
        synchronized (fairnessTerminatingSequence) {
            Stack<Thread> s = new Stack<>();
            for (int i = 0; i < size; i++) {
                s.push(fairnessTerminatingSequence.remove());
            }
            for (int i = 0; i < size; i++) {
                fairnessTerminatingSequence.add(s.pop());
            }
        }
        for(int i = 0; i < size; i++) {
            //So that the player threads will be eliminated in reverse order to the order they were created by
            Thread curr = fairnessTerminatingSequence.remove();
            if (curr != null) {
                String currName = curr.getName();
                String[] afterSplit = currName.split("-");
                char delimiter = afterSplit[0].charAt(0); //TODO: check if you can somehow make the names into a static variable to be reached by this method
                int id = Integer.parseInt(afterSplit[1]);
                if (delimiter == Player.aiThreadName.charAt(0)) {
                    players[id].terminateAI();
                    try {
                        players[id].aiThread.join();
                    } catch (InterruptedException ignored) {}
                }
                else if (delimiter == Player.playerThreadName.charAt(0)) {
                    players[id].terminate();
                    while (players[id].playerThread.isAlive()) {
                        synchronized (this) {
                            notifyAll();
                        }
                        synchronized (table) {
                            table.notifyAll();
                        }
                        synchronized (bothQueues) {
                            bothQueues.notifyAll();
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || checkDeckAndTable() || !checkIfSetExists();
    }

    private boolean checkDeckAndTable() {
        List<Integer> tempDeck = new LinkedList<>(deck);
        for (int i = 0; i < env.config.tableSize; i++)
            if (table.slotToCard[i] != null)
                tempDeck.add(table.slotToCard[i]);

        return env.util.findSets(tempDeck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {

        while(!terminate && !foundSet && ((System.currentTimeMillis() < reshuffleTime && !reverseTimer) || reverseTimer && System.currentTimeMillis() >= reshuffleTime)){
            checkNextSet();
            updateTimerDisplay(false);
        }

        if(foundSet){
            placedCards = false;

            for (Player p : players) {
                table.removeTokens(p.id, currCardSlots);
                p.removeMyTokens(currCardSlots);
                p.removeCardSlotsFromIncomingActionsQueue(currCardSlots);
            }
            table.removeCardsAndTokensInSlots(currCardSlots);
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
            if (checkDeckAndTable())
                terminate = true;
        }
    }

    private void filterQueues(boolean[] keepOrNot) {
        synchronized (bothQueues) {
            LinkedBlockingQueue<int[]> queue1 = new LinkedBlockingQueue<>();
            LinkedBlockingQueue<Player> queue2 = new LinkedBlockingQueue<>();
            int size = fairnessQueueCardsSlots.size();
            for (int i = 0; i < size; i++) {
                try {
                    if (keepOrNot[i]) {
                        queue1.add(fairnessQueueCardsSlots.remove());
                        queue2.add(fairnessQueuePlayers.remove());
                    } else {
                        fairnessQueueCardsSlots.remove();
                        fairnessQueuePlayers.remove();
                    }
                } catch (ArrayIndexOutOfBoundsException ignored) {}
            }
            size = queue1.size();
            for (int i = 0; i < size; i++) {
                fairnessQueueCardsSlots.add(queue1.remove());
                fairnessQueuePlayers.add(queue2.remove());
            }
            bothQueues.notifyAll();
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        if (terminate) return;
        int min = 0;
        for (int i = 0; i < env.config.tableSize; i++) {
            if (table.slotToCard[i] == null) {
                int max = deck.size() - 1;
                int random_num = (int)Math.floor(Math.random()*(max-min+1)+min);
                Integer card = null;
                try {
                    card = deck.remove(random_num);
                } catch (IndexOutOfBoundsException ignored) {}
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

        synchronized (bothQueues){
            try {
                if (!terminate)
                    bothQueues.wait(env.config.tableDelayMillis);
                bothQueues.notifyAll();
            } catch (InterruptedException ignored) {}
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (terminate) return;
        if (!reverseTimer) {
            if (reset) {
                env.ui.setCountdown(env.config.turnTimeoutMillis, false);
                reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            } else
                env.ui.setCountdown((reshuffleTime - System.currentTimeMillis() > practicallyZeroMS) ? reshuffleTime - System.currentTimeMillis() : actualZero, reshuffleTime - System.currentTimeMillis() < env.config.turnTimeoutWarningMillis);
        }
        else {
            if (reset) {
                reshuffleTime = System.currentTimeMillis();
            }
            if (env.config.turnTimeoutMillis == 0)
                env.ui.setElapsed(System.currentTimeMillis() - reshuffleTime);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        placedCards = false;
        for(int i = 0; i < env.config.tableSize; i++) {
            Integer cardValue = table.slotToCard[i];
            if (cardValue != null)
                deck.add(cardValue);
            for(Player p : players){
                if (p.getTokenOnSlot()[i])
                    p.removeMyTokens(new int[]{i});
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
            try {
                if (!fairnessQueuePlayers.isEmpty()) {
                    bothQueues.wait();
                }
            } catch (InterruptedException ignored) {}
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
                Integer temp = table.slotToCard[cardSlots[i]];
                if (temp != null)
                    cardsAsArray[i] = temp;
            }
            if (env.util.testSet(cardsAsArray))
            {
                p.sendMessage(Player.Message.POINT);
                foundSet = true;
                p.removeMyTokens(cardSlots);
            } else {
                p.sendMessage(Player.Message.PENALTY);
                foundSet = false;
            }
        } catch (NoSuchElementException ignored) {}
    }

    private boolean checkIfSetExists() {
        List<Integer> currentTable = new LinkedList<>();
        Collections.addAll(currentTable, table.slotToCard);
        for(int i = 0; i < env.config.tableSize; i++){
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
        terminate();
        env.ui.announceWinner(winners);
    }


    public void iStarted() {
        fairnessTerminatingSequence.add(Thread.currentThread());
    }

    private boolean checkTableForSets() {
        List<Integer> temp = new LinkedList<>(Arrays.asList(table.slotToCard));
        boolean nullPresent = false;
        for (Integer i : temp)
            if (i == null) {
                nullPresent = true;
                break;
            }
        if (nullPresent) return true;
        List<int[]> output = env.util.findSets(temp, 1);
        return output.size() > 0;
    }
    /*
     * Functions to call for testing only
     */
    public void setCardsOnTable() {
        placeCardsOnTable();
    }
    public void removeAllCards() {
        this.removeAllCardsFromTable();
    }
}