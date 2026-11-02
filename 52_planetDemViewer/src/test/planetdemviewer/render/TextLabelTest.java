package planetdemviewer.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextLabelTest {
    @Test
    void theShadowGoesFirstAndTheTextOnePixelToTheLeftAndUp() {
        List<TextLabel> labels = TextLabel.left("Hi", TextStyle.STATUS, 10, 20, TextLabel.WHITE).withShadow();
        assertEquals(TextLabel.left("Hi", TextStyle.STATUS, 10, 20, TextLabel.BLACK), labels.get(0));
        assertEquals(TextLabel.left("Hi", TextStyle.STATUS, 9, 21, TextLabel.WHITE), labels.get(1));
        assertEquals(2, labels.size());
    }
}
