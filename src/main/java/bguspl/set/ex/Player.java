package bguspl.set.ex;

import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Vector;
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
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;


    private final ConcurrentLinkedQueue<int> incomingActions;

    private final boolean[] tokenOnSlot;
    private int tokensPlaced;

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
        incomingActions = new ConcurrentLinkedQueue<int>();
        tokenOnSlot = new boolean[env.config.rows * env.config.columns];
        tokensPlaced = 0;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + "starting.");
        if (!human) createArtificialIntelligence();
        while (!terminate) {
            // TODO check if proper
            try {
                int nextAction = incomingActions.remove();
                if (tokenOnSlot[nextAction]) {
                    table.removeToken(id,nextAction);
                    tokenOnSlot[nextAction] = false;
                    if (tokensPlaced > 0)
                        tokensPlaced--;
                } else {
                    table.placeToken(id,nextAction);
                    tokenOnSlot[nextAction] = true;
                    if (++tokensPlaced == table.legalSetSize) {
                        Vector<int> cards = new Vector<int>();
                        dealer.iGotASet(this, cards);
                        try {
                            cards.wait();
                        } catch (InterruptedException ignored) {}
                    }
                }
            } catch (NoSuchElementException ignored) {
                try {
                    incomingActions.wait();
                } catch (InterruptedException ignored1) {}
            }
        }
        if (!human)
            while (aiThread.isAlive())
                try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                //TODO check if proper
                keyPressSimulator();
                try {
                    incomingActions.wait();
                } catch (InterruptedException ignored) {}
            }
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    private void keyPressSimulator() {
        int[] options = env.config.playerKeys(id);
        Random random = new Random();
        keyPressed(options[(random.nextInt()*options.length)]);
    }
    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        //TODO check if proper
        terminate = true;
        while (playerThread.isAlive())
            try {
                playerThread.join();
            } catch (InterruptedException ignored) {}
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        //TODO check what should happen if more than 3 actions are attempted to be inserted before the player thread attempts to remove them from the queue
        if (incomingActions.size() < 3) {
            incomingActions.add(slot);
            incomingActions.notifyAll();
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        // TODO check if proper
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        env.ui.setFreeze(id,env.config.pointFreezeMillis);
        try {
            playerThread.wait(env.config.pointFreezeMillis); //TODO check if this needs to be playerThread or currentThread()
        } catch (InterruptedException ignored1) {}
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        // TODO check if proper
        env.ui.setFreeze(id,env.config.penaltyFreezeMillis);
        try {
            playerThread.wait(env.config.penaltyFreezeMillis); //TODO check if this needs to be playerThread or currentThread()
        } catch (InterruptedException ignored1) {}
    }

    public int getScore() {
        return score;
    }
}
