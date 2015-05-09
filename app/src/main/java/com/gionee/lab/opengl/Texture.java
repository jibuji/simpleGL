package com.gionee.lab.opengl;

/**
 * Created by jiengfei on 15-5-9.
 */
public interface Texture {
    public int getWidth();

    public int getHeight();

    public void draw(GLCanvas canvas, int x, int y);

    public void draw(GLCanvas canvas, int x, int y, int w, int h);

    public boolean isOpaque();

    public void tint(int color);
}
