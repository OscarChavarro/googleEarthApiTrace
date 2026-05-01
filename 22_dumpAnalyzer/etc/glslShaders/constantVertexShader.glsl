//===========================================================================
//= This vertex shader follows the parameters indicated in the VitralSDK    =
//= shader programming guidelines, as documented in the file                =
//= ./doc/shaderStandards.txt inside the main VitralSDK distribution        =
//= directory.  Please refer to that file to get a clear understanding of   =
//= variable names and common programming patterns used.                    =
//= Note that any variable name change could make the shader incompatible   =
//= with main VitralSDK shader framework!                                   =
//===========================================================================

#version 410 core

uniform mat4 modelViewProjectionLocal;    // input: PM * MVM
uniform int withTexture;                  // input: boolean 1 true 0 false
uniform int withVertexColors;             // input: boolean 1 true 0 false
layout(location = 0) in vec4 PObject;     // input: glVertex3d
layout(location = 1) in vec3 emissionColor;
layout(location = 2) in vec2 uvVertexTextureCoordinate; // input: glTexCoord2d
uniform vec3 diffuseColor;

out vec3 vertexColor;                     // output
out float activateTexture;                // output
out vec2 uvTextureCoordinate;             // output

void main() {
    // Compute homogeneous position of vertex for rasterizer
    gl_Position = modelViewProjectionLocal * PObject;

    // Transform positions and normals from model-space to view-space
    if ( withVertexColors == 1 ) {
        vertexColor = emissionColor;
    }
    else {
        vertexColor = diffuseColor;
    }
    if ( withTexture == 1 ) {
        activateTexture = 1.0;
        uvTextureCoordinate.x = uvVertexTextureCoordinate.x;
        uvTextureCoordinate.y = uvVertexTextureCoordinate.y;
    }
    else {
        activateTexture = 0.0;
    }
}
