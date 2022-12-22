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
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    private volatile boolean terminateAI;

    /**
     * The current score of the player.
     */
    private int score;

    public enum Message{
        PENALTY,
        POINT
    }

    public void removeCardSlotsFromIncomingActionsQueue(int[] currCardSlots) {
        for (Integer i : incomingActions) {
            for (int slot : currCardSlots) {
                if (i == slot)
                    incomingActions.remove(i);
            }
        }
    }
    public final ConcurrentLinkedQueue<Integer> incomingActions;
    private final ConcurrentLinkedQueue<Message> messages;

    private final boolean[] tokenOnSlot;
    private int tokensPlaced;
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
            dealer.notifyAll(); //not sure if this will make it fair exactly.
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

    private void keyPressSimulator() {
        keyPressed(((int)Math.floor(Math.random() * env.config.tableSize)));
    }
    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true;
    }

    public void terminateAI() {
        terminateAI = true;
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

    private boolean slotIsNull(int slot) {
        return table.slotToCard[slot] == null;
    }

    /**
     * Award a point to a player and perform other related actions.
     *
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

    public void sendMessage(Message m) {
        messages.add(m);
        synchronized (incomingActions) {
            incomingActions.notifyAll();
        }
    }

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
     *  @post - the player's score is not increased.
     */
    public void penalty() {

        for (long counter = env.config.penaltyFreezeMillis; counter >= noFreeze; counter -= SECOND)
            try {
                env.ui.setFreeze(id,counter);
                if (counter > noFreeze)
                    synchronized (this) {
                        wait(SECOND);
                    }
            } catch (InterruptedException ignored1) {}
    }

    public void removeMyTokens(int[] cardSlots){
        synchronized (incomingActions) {
            for (int slotId : cardSlots) {
                if (tokenOnSlot[slotId]) {
                    table.removeToken(id, slotId);
                    //tokensPlaced = (tokensPlaced >= noTokens) ? tokensPlaced - 1 : tokensPlaced; //Only reduce tokensPlaced if it's above or at 0
                    //tokenOnSlot[slotId] = false;

                }
            }
            incomingActions.notifyAll();
        }

    }

    public int score() {
        return score;
    }
}
