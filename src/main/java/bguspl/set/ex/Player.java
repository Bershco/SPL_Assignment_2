package bguspl.set.ex;

import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * The dealer object.
     */
    private final Dealer dealer;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    public Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    public Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff the player thread should be terminated at the end of the game, whether by an external event,
     * or if someone wins.
     */
    private volatile boolean terminate;

    /**
     * True iff the AI thread should be terminated at the end of the game, whether by an external event,
     * or if someone wins.
     */
    private volatile boolean terminateAI;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * Used for dealer messages to the player.
     */
    public enum Message{
        PENALTY,
        POINT
    }

    /**
     * Queue used for the incoming actions from keyPressed method
     */
    public final ConcurrentLinkedQueue<Integer> incomingActions;

    /**
     * Queue used for messages from the dealer.
     */
    protected final ConcurrentLinkedQueue<Message> messages;

    /**
     * An array used to keep track of current tokens per slots.
     */
    private final boolean[] tokenOnSlot;

    /**
     * Amount of 'true' values in tokenOnSlot - basically the number of tokens placed.
     */
    private int tokensPlaced;

    /**
     * Magic number (and strings) removers.
     */
    private final int SECOND = 1000;
    private final int noFreeze = 0;
    private final int noTokens = 0;
    public static final String playerThreadName = "Player";
    public static final String aiThreadName = "Computer";

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        incomingActions = new ConcurrentLinkedQueue<>();
        messages = new ConcurrentLinkedQueue<>();
        tokenOnSlot = new boolean[env.config.tableSize];
        tokensPlaced = noTokens;
    }

    /**
     * Getter for tokenOnSlot.
     * @return tokenOnSlot variable.
     */
    public boolean[] getTokenOnSlot(){
        return tokenOnSlot;
    }
    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        synchronized (dealer) {
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + "starting.");
            dealer.iStarted();
            dealer.notifyAll();
        }
        playerThread = Thread.currentThread();
        if (!human) createArtificialIntelligence();
        while (!terminate) {
            try {
                if (!messages.isEmpty())
                    checkMessage();
                int nextAction;
                synchronized (incomingActions){
                    nextAction = incomingActions.remove();
                    incomingActions.notifyAll();
                }

                if (tokenOnSlot[nextAction]) {
                    table.removeToken(id, nextAction);
                    tokenOnSlot[nextAction] = false;
                    if (tokensPlaced > noTokens)
                        tokensPlaced--;
                } else {
                    if (tokensPlaced < env.config.featureSize & dealer.placedCards) {
                        table.placeToken(id, nextAction);
                        tokenOnSlot[nextAction] = true;
                        if (++tokensPlaced == env.config.featureSize) {
                            int[] currSetCardSlots = new int[env.config.featureSize];
                            int cSCSInd = 0;
                            for (int i = 0; i < tokenOnSlot.length; i++) {
                                if (tokenOnSlot[i]) {
                                    if (cSCSInd == env.config.featureSize)
                                        break;
                                    currSetCardSlots[cSCSInd] = i;
                                    cSCSInd++;
                                }
                            }
                            dealer.iGotASet(this, currSetCardSlots);
                        }
                    }
                }

            } catch (NoSuchElementException ignored) {}
        }
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            synchronized (dealer) {
                env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
                dealer.iStarted();
                dealer.notifyAll();
            }
            while (!terminateAI) {
                keyPressSimulator();
            }
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, aiThreadName + "-" + id);
        aiThread.start();
    }

    /**
     * Helper method called from aiThread's run() to generate proper slots.
     */
    private void keyPressSimulator() {
        keyPressed(((int)Math.floor(Math.random() * env.config.tableSize)));
    }
    /**
     * Called when the player thread should be terminated at the end of the game, whether by an external event,
     * or if someone wins.
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * Called when the AI thread should be terminated at the end of the game, whether by an external event,
     * or if someone wins.
     */
    public void terminateAI() {
        terminateAI = true;
    }

    /**
     * Removes certain card slots from the incomingActions queue.
     * @param currCardSlots the card slots to be removed from the queue.
     */
    public void removeCardSlotsFromIncomingActionsQueue(int[] currCardSlots) {
        for (Integer i : incomingActions) {
            for (int slot : currCardSlots) {
                if (i == slot)
                    incomingActions.remove(i);
            }
        }
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     * @pre - incomingActions is of size 2 or less
     * @post - added a slot to incoming actions
     */
    public void keyPressed(int slot) {

        if (slotIsNull(slot)) return;
        if (incomingActions.size() < 3) {
            synchronized (incomingActions){
                if(dealer.placedCards){
                    incomingActions.add(slot);
                    incomingActions.notifyAll();
                }
            }
        }
    }

    /**
     * Checks whether a certain slot is null.
     * @param slot the slot to check.
     * @return true iff the slot checked is null.
     */
    private boolean slotIsNull(int slot) {
        return table.slotToCard[slot] == null;
    }

    /**
     * Award a point to a player and perform other related actions.
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests

        env.ui.setScore(id, ++score);
        env.ui.setFreeze(id,env.config.pointFreezeMillis);
        synchronized (this){
            try {
                wait((env.config.pointFreezeMillis > noFreeze) ? env.config.pointFreezeMillis : 1);
            } catch (InterruptedException ignored1) {}
        }
        env.ui.setFreeze(id,noFreeze);


    }

    /**
     * Helper method for the dealer to send proper messages for the player to receive.
     * @param m the message from the dealer.
     * @pre int size = messages.size()
     * @post message.size() == size + 1
     */
    public void sendMessage(Message m) {
        messages.add(m);
        synchronized (incomingActions) {
            incomingActions.notifyAll();
        }
    }

    /**
     * Helper method for the player to receive the message sent by the dealer.
     */
    private void checkMessage() {
        Message m = messages.remove();
        if (m == Message.PENALTY) {
            penalty();
        }
        else if (m == Message.POINT) {
            point();
        }
        else {
            throw new IllegalArgumentException("Shouldn't have gotten here");
        }
    }
    /**
     * Penalize a player and perform other related actions.
     *  @pre int currScore = score()
     *  @post - score() == currScore
     */
    public void penalty() {
        incomingActions.clear();
        for (long counter = env.config.penaltyFreezeMillis; counter >= noFreeze; counter -= SECOND)
            try {
                env.ui.setFreeze(id,counter);
                if (counter > noFreeze)
                    synchronized (this) {
                        wait(counter > SECOND ? SECOND : env.config.penaltyFreezeMillis % SECOND);
                    }
            } catch (InterruptedException ignored1) {}
    }

    /**
     * Removes all of 'this' player's tokens from the card slots received by the card slots.
     * @param cardSlots the card slots to remove 'this' player's tokens from.
     */
    public void removeMyTokens(int[] cardSlots){
        synchronized (incomingActions) {
            for (int slotId : cardSlots) {
                if (tokenOnSlot[slotId]) {
                    table.removeToken(id, slotId);
                    tokensPlaced = (tokensPlaced >= noTokens) ? tokensPlaced - 1 : tokensPlaced; //Only reduce tokensPlaced if it's above or at 0
                    tokenOnSlot[slotId] = false;
                }
            }
            incomingActions.notifyAll();
        }

    }

    /**
     * Getter for the score variable.
     * @return the 'score' variable.
     */
    public int score() {
        return score;
    }
}
