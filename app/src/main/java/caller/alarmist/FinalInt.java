package caller.alarmist;

/**
 * Created by Ben on 21/12/2015.
 */
public class FinalInt {
    private int value = 0;

    public FinalInt() {
        value = 0;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int x) {
        value = x;
    }

    public int increment() {
        if(value==Integer.MAX_VALUE)
            value = 1;
        else
            value += 1;
        return value;
    }
}
